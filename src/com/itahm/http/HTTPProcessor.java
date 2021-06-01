package com.itahm.http;

import java.io.IOException;

import javax.servlet.http.HttpServletResponse;

import com.itahm.http.Connection.Header;

public class HTTPProcessor extends Thread {
	
	private final HTTPServer server;
	private final Connection connection;
	private final HTTPRequest request;
	
	public HTTPProcessor(HTTPServer server, Connection connection) {
		this.server = server;
		this.connection = connection;
		
		request = connection.createRequest();
		
		setDaemon(true);
		setName("ITAhM HTTPProcessor");
		
		start();
	}
	
	@Override
	public void run() {
		HTTPResponse response = new HTTPResponse();
		//String origin = request.getHeader(Header.ORIGIN.toString());
		String origin = "http://cems.corebrg.com";
		
		switch(this.request.getMethod().toUpperCase()) {
		case "GET":
			this.server.doGet(this.request, response);
			
			break;
		case "OPTIONS":			
			if (origin != null) {
				response.setHeader("Access-Control-Allow-Credentials", "true");
				response.setHeader("Access-Control-Allow-Origin", origin);
				response.setHeader("Access-Control-Allow-Methods","POST, GET, PUT, OPTIONS");
				response.setHeader("Access-Control-Allow-Headers", "Session, File-Target, File-Id");
			}
			
			break;
		case "POST":
			if (origin != null) {
				response.setHeader("Access-Control-Allow-Credentials", "true");
				response.setHeader("Access-Control-Allow-Origin", origin);
			}
			
			this.server.doPost(this.request, response);
			
			break;
		case "PUT":
			if (origin != null) {
				response.setHeader("Access-Control-Allow-Credentials", "true");
				response.setHeader("Access-Control-Allow-Origin", origin);
			}
			
			this.server.doPut(this.request, response);
			
			break;
		default:
			response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
			response.setHeader("Allow", "GET POST OPTIONS");
		}
		
		Session session = this.request.getSession(false);
		
		if (session != null) {
			if (!session.id.equals(request.getRequestedSessionId())) {
				response.setHeader("Access-Control-Expose-Headers", "Set-Session");
				response.setHeader("Set-Session", session.id);
			}
		}
		
		try {
			this.connection.write(response);
		} catch (IOException ioe) {
			ioe.printStackTrace();
		}
	}
}
