package com.itahm.nms.command;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import com.itahm.http.Response;
import com.itahm.json.JSONException;
import com.itahm.json.JSONObject;
import com.itahm.nms.Commander;

public class Add implements Executor{
	private final Map<String, Executor> map = new HashMap<>();
	
	public Add(Commander agent) {
		
		map.put("BRANCH", new Executor() {

			@Override
			public void execute(Response response, JSONObject request, JSONObject session, Connection connection) {
				if (!agent.addBranch(request.getJSONObject("branch"))) {
					response.setStatus(HttpServletResponse.SC_CONFLICT);
				}
			}
			
		});
		
		map.put("FACILITY", new Executor() {

			@Override
			public void execute(Response response, JSONObject request, JSONObject session, Connection connection)
				throws SQLException {
				agent.addFacility(request.getJSONObject("facility"));
			}
			
		});
		
		map.put("ICON", new Executor() {

			@Override
			public void execute(Response response, JSONObject request, JSONObject session, Connection connection) {
				if (agent.addIcon(request.getString("type"), request.getJSONObject("icon")) == null) {
					response.setStatus(HttpServletResponse.SC_CONFLICT);
				}
			}
			
		});
		
		map.put("LINK", new Executor() {

			@Override
			public void execute(Response response, JSONObject request, JSONObject session, Connection connection) {
				if (!agent.addLink(request.getLong("path"))) {
					response.setStatus(HttpServletResponse.SC_CONFLICT);
				}
			}
			
		});
		
		map.put("NODE", new Executor() {

			@Override
			public void execute(Response response, JSONObject request, JSONObject session, Connection connection) {
				JSONObject node = agent.addNode(request.getJSONObject("node"));
				
				if (node == null) {
					response.setStatus(HttpServletResponse.SC_CONFLICT);
				}
				else {
					response.write(node);
				}
			}
			
		});
		
		map.put("PATH", new Executor() {

			@Override
			public void execute(Response response, JSONObject request, JSONObject session, Connection connection) {
				if (!agent.addPath(request.getLong("nodeFrom"), request.getLong("nodeTo"))) {
					response.setStatus(HttpServletResponse.SC_CONFLICT);
				}
			}
			
		});
		
		map.put("PROFILE", new Executor() {

			@Override
			public void execute(Response response, JSONObject request, JSONObject session, Connection connection) {
				if (!agent.addProfile(request.getString("name"), request.getJSONObject("profile"))) {
					response.setStatus(HttpServletResponse.SC_CONFLICT);
				}
			}
			
		});
		
		map.put("RACK", new Executor() {

			@Override
			public void execute(Response response, JSONObject request, JSONObject session, Connection connection) {
				if (!agent.addRack(request.getJSONObject("rack"))) {
					response.setStatus(HttpServletResponse.SC_CONFLICT);
				}
			}
			
		});
		
		map.put("USER", new Executor() {

			@Override
			public void execute(Response response, JSONObject request, JSONObject session, Connection connection)
				throws SQLException {
				if (!agent.addUser(request.getString("id"), request.getJSONObject("user"))) {
					response.setStatus(HttpServletResponse.SC_CONFLICT);
				}
			}
			
		});
	}
	
	@Override
	public void execute(Response response, JSONObject request, JSONObject session, Connection connection)
		throws SQLException {
		String target = request.getString("target");
		Executor executor = this.map.get(target.toUpperCase());
		
		if (executor == null) {
			throw new JSONException("Target is not found.");
		} else {
			executor.execute(response, request, session, connection);
			
			try (PreparedStatement pstmt = connection.prepareStatement("INSERT INTO"+
				" t_audit values (?, 'add', ?, ?);")) {
				pstmt.setString(1, session.getString("id"));
				pstmt.setString(2, target);
				pstmt.setLong(3, System.currentTimeMillis());
				
				pstmt.executeUpdate();
			}
		}
	}
	
}
