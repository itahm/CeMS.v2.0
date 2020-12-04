package com.itahm.servlet;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.servlet.ServletConfig;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.itahm.json.JSONException;
import com.itahm.json.JSONObject;
import com.itahm.service.NMS;
import com.itahm.service.Serviceable;
import com.itahm.service.SignIn;
import com.itahm.util.Util;

public class Servlet extends HttpServlet  {

	private static final long serialVersionUID = 1L;
	private static String LICENSE = null;
	private Path root;
	private final Map<String, Serviceable> services = new LinkedHashMap<>();
	
	@Override
	public void init(ServletConfig config) {
		if (LICENSE != null && !Util.isValidAddress(LICENSE)) {
			new Exception("Unauthorized License.").printStackTrace();
			
			return;
		}
		
		String value;		
		
		value = config.getInitParameter("root");
		
		if (value == null) {
			new Exception("Check Configuration : root.").printStackTrace();;
			
			return;
		}
		
		this.root = Path.of(value).resolve("data");
		
		if (!Files.isDirectory(this.root)) {
			try {
				Files.createDirectories(this.root);
			} catch (IOException ioe) {
				ioe.printStackTrace();
				
				return;
			}
		}
		
		
		try {
			services.put("SIGNIN", new SignIn(root));
			services.put("NMS", new NMS(root));
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	@Override
	public void destroy() {
		for (String name : this.services.keySet()) {
			try {
				this.services.get(name).close();
			} catch (IOException ioe) {
				ioe.printStackTrace();
			}
		}
		
		super.destroy();
	}
	
	@Override
	public void doPost(HttpServletRequest request, HttpServletResponse response) {
		String origin = request.getHeader("origin");
		
		if (origin != null) {
			response.setHeader("Access-Control-Allow-Credentials", "true");
			response.setHeader("Access-Control-Allow-Origin", origin);
		}
		
		int cl = request.getContentLength();
		
		if (cl < 0) {
			response.setStatus(HttpServletResponse.SC_LENGTH_REQUIRED);
		}
		else {
			try (InputStream is = request.getInputStream()) {
				byte [] buffer = new byte [cl];
				JSONObject data;
				Serviceable service;
				String name;
				
				for (int i=0; i<cl;) {
					i += is.read(buffer, i, cl - i);
					if (i < 0) {
						break;
					}
				}
			
				data = new JSONObject(new String(buffer, StandardCharsets.UTF_8.name()));
	
				if (!data.has("command")) {
					throw new JSONException("Command not found.");
				}
				
				switch (data.getString("command").toLowerCase()) {
				case "SERVICE":
					JSONObject body = new JSONObject();
					
					for (String key : this.services.keySet()) {
						service = this.services.get(key);
				
						if (!key.equals("SIGNIN")) {
							body.put(key.toLowerCase(), service == null? false: true);
						}
					}
					
					writeResponse(response, body.toString());
					
					break;
				case "START":
					name = data.getString("service").toUpperCase();
					
					if (this.services.containsKey(name)) {
						service = this.services.get(name);
						
						if (service == null) {
							switch (name) {
							case "NMS":
								service = new NMS(this.root.resolve("data"));
								
								this.services.put(name, service);
								
								break;
							default :
								throw new JSONException("Service is reserved");
							}
						} else {
							throw new JSONException("Service is running");
						}
					} else {
						throw new JSONException("Service is not found");
					}
					
					break;
				case "STOP":
					name = data.getString("service").toUpperCase();
					if (this.services.containsKey(name)) {
						service = this.services.get(name);
						
						if (service == null) {
							throw new JSONException("Service is not running");
						} else {
							service.close();
							
							this.services.put(name, null);
						}
					} else {
						throw new JSONException("Service is not found");
					}
					
					break;
				default:
					for (String key : this.services.keySet()) {
						if (this.services.get(key).service(new ServletRequest(request), new ServletResponse(response), data)) {
							return;
						}
					}
					
					response.setStatus(HttpServletResponse.SC_NOT_FOUND);
				}
			} catch (JSONException | UnsupportedEncodingException e) {				
				response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
				
				writeResponse(response, new JSONObject()
					.put("error", e.getMessage())
					.toString());
			} catch (Exception e) {
				StringWriter sw = new StringWriter();
				
				e.printStackTrace(new PrintWriter(sw));
				
				System.out.println(sw);
				
				response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
			}
		}
	}
	
	public static final void writeResponse(HttpServletResponse response, String body) {
		try (ServletOutputStream sos = response.getOutputStream()) {
			sos.write(body.getBytes(StandardCharsets.UTF_8.name()));
			sos.flush();
		} catch (IOException ioe) {
			StringWriter sw = new StringWriter();
			
			ioe.printStackTrace(new PrintWriter(sw));
			
			System.out.println(sw);
			
			response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
		}
	}
}
