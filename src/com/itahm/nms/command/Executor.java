package com.itahm.nms.command;

import java.sql.Connection;
import java.sql.SQLException;

import com.itahm.http.Response;
import com.itahm.json.JSONObject;

public interface Executor {
	public void execute(Response response, JSONObject request, JSONObject session, Connection connection) throws SQLException;
}
