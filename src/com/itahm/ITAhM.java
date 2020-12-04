package com.itahm;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServletResponse;

import com.itahm.http.HTTPServer;
import com.itahm.http.HTTPResponse;
import com.itahm.http.HTTPRequest;
import com.itahm.json.JSONException;
import com.itahm.json.JSONObject;
import com.itahm.service.NMS;
import com.itahm.service.Serviceable;
import com.itahm.service.SignIn;
import com.itahm.util.Util;

public class ITAhM extends HTTPServer {
	
	public static String LICENSE = null;
	
	private final Path root;
	private Boolean isClosed = false;
	private final Map<String, Serviceable> services = new LinkedHashMap<>();
	
	public ITAhM() throws Exception {
		this("0.0.0.0", 2014);
	}
	
	public ITAhM(int tcp) throws Exception {
		this("0.0.0.0", tcp);
	}
	
	public ITAhM(int tcp, Path path) throws Exception {
		this("0.0.0.0", tcp, path);
	}
	
	public ITAhM(String ip, int tcp) throws Exception {
		this(ip, tcp, Path.of(ITAhM.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent());
	}

	public ITAhM(String ip, int tcp, Path path) throws Exception {
		super(ip, tcp);
		
		if (LICENSE != null && !Util.isValidAddress(LICENSE)) {
			throw new Exception("Unauthorized License.");
		}
		
		System.out.format("ITAhM HTTP Server started with TCP %d.\n", tcp);
		
		this.root = path;
		
		Path root = path.resolve("data");
		
		if (!Files.isDirectory(root)) {
			Files.createDirectories(root);
		}
		
		services.put("SIGNIN", new SignIn(root));
		services.put("NMS", new NMS(root));
	}
	
	@Override
	public void doGet(HTTPRequest request, HTTPResponse response) {
		String uri = request.getRequestURI();
		
		if ("/".equals(uri)) {
			uri = "/index.html";
		}
		
		Path path = this.root.resolve(uri.substring(1));
		
		if (!Pattern.compile("^/data/.*").matcher(uri).matches() && Files.isRegularFile(path)) {
			try {
				response.write(path);
			} catch (IOException e) {
				response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
			}
		}
		else {
			response.setStatus(HttpServletResponse.SC_NOT_FOUND);
		}
	}
	
	@Override
	public void doPost(HTTPRequest request, HTTPResponse response) {
		/*if (NMS.EXPIRE > 0 && (System.currentTimeMillis() > NMS.EXPIRE)) {
			response.setStatus(Response.Status.UNAVAILABLE);
			
			return;
		}
		*/
		try {
			JSONObject data = new JSONObject(new String(request.read(), StandardCharsets.UTF_8.name()));
			
			if (!data.has("command")) {
				throw new JSONException("Command not found.");
			}
			
			Serviceable service;
			String name;
			
			switch (data.getString("command").toUpperCase()) {
			case "SERVICE":
				JSONObject body = new JSONObject();
				
				for (String key : this.services.keySet()) {
					service = this.services.get(key);
			
					if (!key.equals("SIGNIN")) {
						body.put(key.toLowerCase(), service == null? false: true);
					}
				}
				
				response.write(body.toString());
				
				return;
			case "START":
				name = data.getString("service").toUpperCase();
				
				if (this.services.containsKey(name)) {
					service = this.services.get(name);
					
					if (service == null) {
						switch (name) {
						case "NMS":
							try {
								service = new NMS(this.root.resolve("data"));
								
								this.services.put(name, service);
							} catch (Exception e) {
								e.printStackTrace();
								
								response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
								
								return;
							}
							
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
				
				return;
			case "STOP":
				name = data.getString("service").toUpperCase();
				if (this.services.containsKey(name)) {
					service = this.services.get(name);
					
					if (service == null) {
						throw new JSONException("Service is not running");
					} else {
						try {
							service.close();
							
							this.services.put(name, null);
						} catch (IOException ioe) {
							ioe.printStackTrace();
						}
					}
				} else {
					throw new JSONException("Service is not found");
				}
				
				return;
				
			default:
				for (String key : this.services.keySet()) {
					if (this.services.get(key).service(request, response, data)) {
						return;
					}
				}
				
				response.setStatus(HttpServletResponse.SC_NOT_FOUND);
				
				return;
			}
		} catch (JSONException | UnsupportedEncodingException e) {
			response.write(new JSONObject()
				.put("error", e.getMessage())
				.toString());
		}
		
		response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
	}
	
	public void close() {
		synchronized (this.isClosed) {
			if (this.isClosed) {
				return;
			}
			
			this.isClosed = true;
		}
		
		for (String name : this.services.keySet()) {
			try {
				this.services.get(name).close();
			} catch (IOException ioe) {
				ioe.printStackTrace();
			}
		}
		
		try {
			super.close();
		} catch (IOException ioe) {
			ioe.printStackTrace();
		}
	}
	
	public static void main(String[] args) throws Exception {
		Path root = null;
		String ip = "0.0.0.0";
		int tcp = 2014;
		
		for (int i=0, _i=args.length; i<_i; i++) {
			if (args[i].indexOf("-") != 0) {
				continue;
			}
			
			switch(args[i].substring(1).toUpperCase()) {
			case "ROOT":
				root = Path.of(args[++i]);
				
				break;
			case "TCP":
				try {
					tcp = Integer.parseInt(args[++i]);
				} catch (NumberFormatException nfe) {}
				
				break;
			}
		}
		
		ITAhM itahm = root == null? new ITAhM(ip, tcp): new ITAhM(ip, tcp, root);
		
		Runtime.getRuntime().addShutdownHook(
			new Thread() {
				
				@Override
				public void run() {
					if (itahm != null) {
						itahm.close();
					}
				}
			}
		);
	}
}
