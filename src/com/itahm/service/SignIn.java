package com.itahm.service;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import org.h2.jdbcx.JdbcConnectionPool;

import com.itahm.http.Reques;
import com.itahm.http.Response;
import com.itahm.http.Session;
import com.itahm.json.JSONException;
import com.itahm.json.JSONObject;
import com.itahm.nms.command.Executor;

public class SignIn implements Serviceable {

	private final static String MD5_ROOT = "63a9f0ea7bb98050796b649e85481845";
	private final static int SESS_TIMEOUT = 3600;
	
	private final Map<String, Executor> map = new HashMap<>();
	private final JdbcConnectionPool connPool;
	
	public SignIn(Path root) throws Exception {
		connPool = JdbcConnectionPool.create(String.format("jdbc:h2:%s", root.resolve("account").toString()), "sa", "");
		
		try (Connection c = connPool.getConnection()) {
			c.setAutoCommit(false);
			
			try {
				/**
				 * ACCOUNT
				 */
				try (Statement stmt = c.createStatement()) {
					stmt.executeUpdate("CREATE TABLE IF NOT EXISTS account"+
						" (username VARCHAR NOT NULL"+
						", password VARCHAR NOT NULL"+
						", level INT NOT NULL DEFAULT 0"+
						", PRIMARY KEY(username));");
				}
				
				try (Statement stmt = c.createStatement()) {
					try (ResultSet rs = stmt.executeQuery("SELECT COUNT(username) FROM account;")) {
						if (!rs.next() || rs.getLong(1) == 0) {
							try (PreparedStatement pstmt = c.prepareStatement("INSERT INTO account"+
								" (username, password, level)"+
								" VALUES ('root', ?, 0);")) {
								pstmt.setString(1, MD5_ROOT);
								
								pstmt.executeUpdate();
							}
						}
					}
				}
				
				c.commit();
			} catch (SQLException sqle) {
				c.rollback();
				
				throw sqle;
			}
		}
		
		map.put("ADD", new Executor() {
			
			@Override
			public void execute(Response response, JSONObject request, JSONObject session, Connection connection)
					throws SQLException {
				JSONObject account = request.getJSONObject("account");
				
				try {
					connection.setAutoCommit(false);
					
					try (PreparedStatement pstmt = connection.prepareStatement("select username FROM account WHERE username=?")) {
						pstmt.setString(1, account.getString("username"));
						
						try (ResultSet rs = pstmt.executeQuery()) {
							
							if (rs.next()) {
								response.setStatus(HttpServletResponse.SC_CONFLICT);
								
								return;
							}
						}
					}
					
					try (PreparedStatement pstmt = connection.prepareStatement("INSERT INTO account (username, password, level)"+
						" VALUES (?, ?, ?);")) {
						pstmt.setString(1, account.getString("username"));
						pstmt.setString(2, account.getString("password"));
						pstmt.setInt(3, account.getInt("level"));
						
						pstmt.executeUpdate();
					}
				
					connection.commit();
				} catch (SQLException sqle) {
					connection.rollback();
					
					throw sqle;
				}
			}
			
		});

		map.put("GET", new Executor() {

			@Override
			public void execute(Response response, JSONObject request, JSONObject session, Connection connection) throws SQLException {
				if (request.has("username")) {
					String username = request.getString("username");
				
					try (PreparedStatement pstmt = connection.prepareStatement("SELECT"+
							" username, password, level"+
							" FROM account"+
							" WHERE username=?;")) {
							pstmt.setString(1, username);
							
							try (ResultSet rs = pstmt.executeQuery()) {
								if (rs.next()) {
									response.write(new JSONObject()
										.put("username", rs.getString(1))
										.put("password", rs.getString(2))
										.put("level", rs.getInt(3)));
								} else {
									response.setStatus(HttpServletResponse.SC_NO_CONTENT);				
								}
							}
						}
				} else {
					try (Statement stmt = connection.createStatement()) {
						try (ResultSet rs = stmt.executeQuery("SELECT username, password, level FROM account;")) {
							JSONObject accountData = new JSONObject();
						
							while (rs.next()) {
								accountData.put(rs.getString(1), new JSONObject()
									.put("username", rs.getString(1))
									.put("password", rs.getString(2))
									.put("level", rs.getInt(3)));
							}
							
							response.write(accountData);
						}
					}
				}
			}
		});
		
		map.put("REMOVE", new Executor() {

			@Override
			public void execute(Response response, JSONObject request, JSONObject session, Connection connection) throws SQLException {
				String username = request.getString("username");
				
				if (session.getInt("level") > 0 || username.equals("")) {
					response.setStatus(HttpServletResponse.SC_FORBIDDEN);
				} else {
					try (PreparedStatement pstmt = connection.prepareStatement("DELETE FROM account WHERE username=?;")) {
						pstmt.setString(1, username);
						
						pstmt.executeUpdate();
					}
				}
			}
		});
		
		map.put("SET", new Executor() {
			private final Map<String, Executor> map = new HashMap<>();
			
			{
				map.put("PASSWORD", new Executor () {
					@Override
					public void execute(Response response, JSONObject request, JSONObject session, Connection connection) throws SQLException {
						String username = request.getString("username");
						
						if (session.getInt("level") > 0 && !username.equals(session.getString("username"))) {
							response.setStatus(HttpServletResponse.SC_FORBIDDEN);
						} else {
							try (PreparedStatement pstmt = connection.prepareStatement("UPDATE account"+
								" SET password=?"+
								" WHERE username=?;")) {
								pstmt.setString(1, request.getString("password"));
								pstmt.setString(2, username);
								
								pstmt.executeUpdate();
							}
						}
					};
				});
			}
			
			@Override
			public void execute(Response response, JSONObject request, JSONObject session, Connection connection) throws SQLException {
				Executor executor = this.map.get(request.getString("key").toUpperCase());
				
				if (executor == null) {
					throw new JSONException("Key is not found.");
				}
				
				executor.execute(response, request, session, connection);
			};
		});
		
		map.put("SIGNIN", new Executor() {

			@Override
			public void execute(Response response, JSONObject request, JSONObject session, Connection connection) {
				response.write(session.toString());		
			};
		});
		
		map.put("SIGNOUT", new SignOut());
	}
	
	@Override
	public void close() {
	}

	@Override
	synchronized public boolean service(Reques request, Response response, JSONObject data) {
		Session session = request.getSession(false);
		
		try {
			if (session == null) {
				trySignIn(request, response, data);
			
				return true;
			}
			
			if (!data.getString("target").equalsIgnoreCase("account")) {
				return false;
			}
			
			Executor executor = this.map.get(data.getString("command").toUpperCase());
			
			if (executor == null) {
				return false;
			}
			
			if (executor instanceof SignOut) {
				session.invalidate();
			} else {
				try (Connection c = this.connPool.getConnection()) {
					executor.execute(response, data, (JSONObject)session.getAttribute("account"), c);
				}
			}
		} catch (JSONException e) {
			response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
			
			response.write(new JSONObject().
				put("error", e.getMessage()));
		} catch (Exception e) {
			response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
			
			response.write(new JSONObject().
				put("error", e.getMessage()));
			
			e.printStackTrace();
		}
		
		return true;
	}
	
	private void trySignIn(Reques request, Response response, JSONObject data) throws SQLException{
		if (data.getString("command").equalsIgnoreCase("SIGNIN")) {
			try (Connection c = connPool.getConnection()) {
				try (PreparedStatement pstmt = c.prepareStatement("SELECT username, level FROM account"+
					" WHERE username=? AND password=?;")) {
					pstmt.setString(1, data.getString("username"));
					pstmt.setString(2, data.getString("password"));
					
					try (ResultSet rs = pstmt.executeQuery()) {
						if (rs.next()) {
							JSONObject account = new JSONObject()
								.put("username", rs.getString(1))
								.put("level", rs.getInt(2));
							
							Session session = request.getSession(true);
							
							session.setAttribute("account", account);
							
							session.setMaxInactiveInterval(SESS_TIMEOUT);
							
							response.setHeader("Set-Session", session.id);
							
							response.write(account.toString());
							
							return;
						}
					}
				}
			}
		}
		
		response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
	}
	
	class SignOut implements Executor {

		@Override
		public void execute(Response response, JSONObject request, JSONObject session, Connection connection)
			throws SQLException {
		}
	}
}
