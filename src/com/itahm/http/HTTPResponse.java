package com.itahm.http;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import com.itahm.json.JSONObject;

public class HTTPResponse implements Response {
	public static Map<Integer, String> statusMap = new HashMap<>();
	
	static {
		statusMap.put(HttpServletResponse.SC_OK, "OK");
		statusMap.put(HttpServletResponse.SC_NO_CONTENT, "No Content");
		statusMap.put(HttpServletResponse.SC_BAD_REQUEST, "Bad request");
		statusMap.put(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized");
		statusMap.put(HttpServletResponse.SC_FORBIDDEN, "Forbidden");
		statusMap.put(HttpServletResponse.SC_NOT_FOUND, "Not found");
		statusMap.put(HttpServletResponse.SC_METHOD_NOT_ALLOWED, "Method Not Allowed");
		statusMap.put(HttpServletResponse.SC_CONFLICT, "Conflict");
		statusMap.put(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Internal Server Error");
		statusMap.put(HttpServletResponse.SC_NOT_IMPLEMENTED, "Not Implemented");
		statusMap.put(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Service Unavailable");
		statusMap.put(HttpServletResponse.SC_HTTP_VERSION_NOT_SUPPORTED, "HTTP Version Not Supported");
	}
	
	public final static String CRLF = "\r\n";
	public final static String FIELD = "%s: %s"+ CRLF;
	
	private final Map<String, String> header = new HashMap<String, String>();
	private int status = HttpServletResponse.SC_OK;
	private byte [] body = new byte [0];
	
	public int getStatus() {
		return this.status;
	}
	
	public void write(JSONObject body) {
		write(body.toString());
	}
	
	public void write(byte [] body) {
		this.body = body;
	}
	
	public void write(String body) {
		try {
			this.body = body.getBytes(StandardCharsets.UTF_8.name());
		} catch (UnsupportedEncodingException e) {
			this.body = new byte [0];
		}
	}
	
	public void write(File url) throws IOException {
		write(url.toPath());
	}
	
	public void write(Path url) throws IOException {
		write(Files.readAllBytes(url));
		
		setHeader("Content-type", Files.probeContentType(url));
	}
	
	public byte [] read() {
		return this.body;
	}
	
	@Override
	public void setHeader(String name, String value) {
		this.header.put(name, value);
	}
	
	public ByteBuffer build() throws IOException {
		StringBuilder sb = new StringBuilder();
		Iterator<String> iterator;		
		String key;
		byte [] header;
		byte [] message;
		
		sb.append(String.format("HTTP/1.1 %d %s" +CRLF, this.status, statusMap.get(this.status)));
		sb.append(String.format(FIELD, "Content-Length", String.valueOf(this.body.length)));
		
		iterator = this.header.keySet().iterator();
		while(iterator.hasNext()) {
			key = iterator.next();
			
			sb.append(String.format(FIELD, key, this.header.get(key)));
		}
		
		sb.append(CRLF);
		
		header = sb.toString().getBytes(StandardCharsets.US_ASCII.name());
		
		message = new byte [header.length + this.body.length];
		
		System.arraycopy(header, 0, message, 0, header.length);
		System.arraycopy(this.body, 0, message, header.length, this.body.length);
		
		return ByteBuffer.wrap(message);
	}

	@Override
	public void setStatus(int code) {
		this.status = code;
	}

	
}
