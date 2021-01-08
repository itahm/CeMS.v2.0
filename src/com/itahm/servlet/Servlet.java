package com.itahm.servlet;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.servlet.ServletConfig;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.itahm.json.JSONException;
import com.itahm.json.JSONObject;
import com.itahm.service.NMS;
import com.itahm.util.Util;

public class Servlet extends HttpServlet  {

	private static final long serialVersionUID = 1L;
	private static String LICENSE = null;
	private Path root;
	private NMS service;
	
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
			service= new NMS(root);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	@Override
	public void destroy() {
		this.service.close();
		
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
				
				for (int i=0; i<cl;) {
					i += is.read(buffer, i, cl - i);
					if (i < 0) {
						break;
					}
				}
			
				data = new JSONObject(new String(buffer, StandardCharsets.UTF_8.name()));
	
				if (!data.has("command")) {
					throw new JSONException("Command is not found.");
				}
				
				synchronized (this.service) {
					switch (data.getString("command").toUpperCase()) {
					case "START":
						synchronized (this.service) {
							if (this.service == null) {
								this.service = new NMS(this.root.resolve("data"));
							}
						}
						break;
					case "STOP":
						synchronized (this.service) {
							if (this.service == null) {
								response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
							} else {
								this.service.close();
								
								this.service = null;
							}
						}
						
						break;
					default:
						if (this.service == null) {
							response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
						} else {
							this.service.service(new ServletRequest(request), new ServletResponse(response), data);
						}
					}
				}
			} catch (JSONException | UnsupportedEncodingException e) {				
				response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
				
				writeResponse(response, new JSONObject()
					.put("error", e.getMessage())
					.toString());
			} catch (Exception e) {				
				response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
				
				writeResponse(response, new JSONObject()
					.put("error", e.getMessage())
					.toString());
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
