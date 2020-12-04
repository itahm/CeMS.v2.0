package com.itahm.http;

import com.itahm.json.JSONObject;

public interface Response {
	public void write(String body);
	public void write(JSONObject body);
	public void setStatus(int status);
	public void setHeader(String name, String value);
}
