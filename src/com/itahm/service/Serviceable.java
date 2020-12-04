package com.itahm.service;

import java.io.Closeable;

import com.itahm.http.Reques;
import com.itahm.http.Response;
import com.itahm.json.JSONObject;

public interface Serviceable extends Closeable  {
	public boolean service(Reques request, Response response, JSONObject data);
}
