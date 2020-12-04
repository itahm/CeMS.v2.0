package com.itahm.servlet;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletResponse;

import com.itahm.json.JSONObject;

public class ServletResponse implements com.itahm.http.Response {
	private final HttpServletResponse response;
	
	public ServletResponse(HttpServletResponse response) {
		this.response = response;
	}
	
	@Override
	public void write(String body) {
		try (ServletOutputStream sos = this.response.getOutputStream()) {
			sos.write(body.getBytes(StandardCharsets.UTF_8.name()));
			
			sos.flush();
			
		} catch (IOException ioe) {
			ioe.printStackTrace();
			
			response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
		}
	}

	@Override
	public void setStatus(int status) {
		response.setStatus(status);
	}

	@Override
	public void write(JSONObject body) {
		write(body.toString());
	}

	@Override
	public void setHeader(String name, String value) {
		this.response.setHeader(name, value);
	}
}
