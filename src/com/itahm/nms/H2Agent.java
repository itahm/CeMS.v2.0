package com.itahm.nms;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.h2.jdbcx.JdbcConnectionPool;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.TimeTicks;
import org.snmp4j.smi.Variable;

import com.itahm.json.JSONArray;
import com.itahm.json.JSONObject;
import com.itahm.nms.Bean.Profile;
import com.itahm.nms.Commander;
import com.itahm.nms.NodeEventReceivable;
import com.itahm.nms.NodeManager;
import com.itahm.nms.SmartSearch;
import com.itahm.nms.Batch;
import com.itahm.nms.Bean.*;
import com.itahm.nms.Bean.Rule.Rolling;
import com.itahm.nms.node.ICMPNode;
import com.itahm.nms.node.PDUManager;
import com.itahm.nms.node.SNMPNode;
import com.itahm.nms.node.SeedNode.Protocol;
import com.itahm.nms.node.TCPNode;
import com.itahm.nms.parser.*;
import com.itahm.util.Listener;
import com.itahm.util.Network;
import com.itahm.util.Util;

public class H2Agent implements Commander, NodeEventReceivable, Listener, Closeable {
	
	private Boolean isClosed = false;
	private Long nextNodeID = Long.valueOf(1);
	private Long nextEventID = Long.valueOf(1);
	private final Listener nms;
	private final static ResourceManager resourceManager = new ResourceManager();
	private final NodeManager nodeManager;
	protected final JdbcConnectionPool connPool;
	private final Batch batch;
	private final static Map<String, Rule> ruleMap = new ConcurrentHashMap<>();
	private final Config config = new Config();
	private final Path root;
	
	enum Parser {
		HRPROCESSORLOAD(new HRProcessorLoad(resourceManager)),
		HRSTORAGEMEMORY(new HRStorageMemory(resourceManager)),
		HRSTORAGEUSED(new HRStorage(resourceManager)),
		IFINOCTETS(new IFOctetsIn(resourceManager)),
		IFOUTOCTETS(new IFOctetsOut(resourceManager)),
		IFINERRORS(new IFErrorsIn(resourceManager)),
		IFOUTERRORS(new IFErrorsOut(resourceManager)),
		RESPONSETIME(new ResponseTime(resourceManager));
		
		private final Parseable parser;
		
		private Parser(Parseable parser) {
			this.parser = parser;
		}
		
		public Parseable getInstance() {
			return this.parser;
		}
		
		public String toString() {
			return this.toString();
		}
	}
	
	{
		try {
			Class.forName("org.h2.Driver");
		} catch (ClassNotFoundException cnfe) {
			cnfe.printStackTrace();
		}
		
		ruleMap.put("1.3.6.1.2.1.1.1", new Rule("1.3.6.1.2.1.1.1", "sysDescr", "DisplayString"));
		ruleMap.put("1.3.6.1.2.1.1.2", new Rule("1.3.6.1.2.1.1.2", "sysObjectID", "OBJECT IDENTIFIER"));
		ruleMap.put("1.3.6.1.2.1.1.3", new Rule("1.3.6.1.2.1.1.3", "sysUpTime", "TimeTicks"));
		ruleMap.put("1.3.6.1.2.1.1.5", new Rule("1.3.6.1.2.1.1.5", "sysName", "DisplayString"));
		ruleMap.put("1.3.6.1.2.1.2.2.1.2", new Rule("1.3.6.1.2.1.2.2.1.2", "ifDescr", "DisplayString"));
		ruleMap.put("1.3.6.1.2.1.2.2.1.3", new Rule("1.3.6.1.2.1.2.2.1.3", "ifType", "INTEGER"));
		ruleMap.put("1.3.6.1.2.1.2.2.1.5", new Rule("1.3.6.1.2.1.2.2.1.5", "ifSpeed", "Gauge"));
		ruleMap.put("1.3.6.1.2.1.2.2.1.6", new Rule("1.3.6.1.2.1.2.2.1.6", "ifPhysAddress", "PhysAddress"));
		ruleMap.put("1.3.6.1.2.1.2.2.1.7", new Rule("1.3.6.1.2.1.2.2.1.7", "ifAdminStatus", "INTEGER"));
		ruleMap.put("1.3.6.1.2.1.2.2.1.8", new Rule("1.3.6.1.2.1.2.2.1.8", "ifOperStatus", "INTEGER"));
		ruleMap.put("1.3.6.1.2.1.2.2.1.10", new Rule("1.3.6.1.2.1.2.2.1.10", "ifInOctets", "Counter", Rolling.COUNTER));
		ruleMap.put("1.3.6.1.2.1.2.2.1.14", new Rule("1.3.6.1.2.1.2.2.1.14", "ifInErrors", "Counter", Rolling.COUNTER));
		ruleMap.put("1.3.6.1.2.1.2.2.1.16", new Rule("1.3.6.1.2.1.2.2.1.16", "ifOutOctets", "Counter", Rolling.COUNTER));
		ruleMap.put("1.3.6.1.2.1.2.2.1.20", new Rule("1.3.6.1.2.1.2.2.1.20", "ifOutErrors", "Counter", Rolling.COUNTER));
		ruleMap.put("1.3.6.1.2.1.4.22.1.2", new Rule("1.3.6.1.2.1.4.22.1.2", "ipNetToMediaPhysAddress", "PhysAddress"));
		ruleMap.put("1.3.6.1.2.1.4.22.1.4", new Rule("1.3.6.1.2.1.4.22.1.4", "ipNetToMediaType", "INTEGER"));
		ruleMap.put("1.3.6.1.2.1.25.1.1", new Rule("1.3.6.1.2.1.25.1.1", "hrSystemUptime", "TimeTicks"));
		ruleMap.put("1.3.6.1.2.1.25.2.3.1.2", new Rule("1.3.6.1.2.1.25.2.3.1.2", "hrStorageType", "OBJECT IDENTIFIER"));
		ruleMap.put("1.3.6.1.2.1.25.2.3.1.3", new Rule("1.3.6.1.2.1.25.2.3.1.3", "hrStorageDescr", "DisplayString"));
		ruleMap.put("1.3.6.1.2.1.25.2.3.1.4", new Rule("1.3.6.1.2.1.25.2.3.1.4", "hrStorageAllocationUnits", "INTEGER"));
		ruleMap.put("1.3.6.1.2.1.25.2.3.1.5", new Rule("1.3.6.1.2.1.25.2.3.1.5", "hrStorageSize", "INTEGER"));
		ruleMap.put("1.3.6.1.2.1.25.2.3.1.6", new Rule("1.3.6.1.2.1.25.2.3.1.6", "hrStorageUsed", "INTEGER", Rolling.GAUGE));
		ruleMap.put("1.3.6.1.2.1.25.3.3.1.2", new Rule("1.3.6.1.2.1.25.3.3.1.2", "hrProcessorLoad", "INTEGER"));
		ruleMap.put("1.3.6.1.2.1.31.1.1.1.1", new Rule("1.3.6.1.2.1.31.1.1.1.1", "ifName", "DisplayString"));
		ruleMap.put("1.3.6.1.2.1.31.1.1.1.6", new Rule("1.3.6.1.2.1.31.1.1.1.6", "ifHCInOctets", "Counter64", Rolling.COUNTER));
		ruleMap.put("1.3.6.1.2.1.31.1.1.1.10", new Rule("1.3.6.1.2.1.31.1.1.1.10", "ifHCOutOctets", "Counter64", Rolling.COUNTER));
		ruleMap.put("1.3.6.1.2.1.31.1.1.1.15", new Rule("1.3.6.1.2.1.31.1.1.1.15", "ifHighSpeed ", "Gauge32"));
		ruleMap.put("1.3.6.1.2.1.31.1.1.1.18", new Rule("1.3.6.1.2.1.31.1.1.1.18", "ifAlias", "DisplayString"));
		ruleMap.put("1.3.6.1.4.1.9.9.109.1.1.1.1.3", new Rule("1.3.6.1.4.1.9.9.109.1.1.1.1.3", "cpmCPUTotal5sec", "INTEGER", Rolling.GAUGE));
		ruleMap.put("1.3.6.1.4.1.9.9.109.1.1.1.1.6", new Rule("1.3.6.1.4.1.9.9.109.1.1.1.1.6", "cpmCPUTotal5secRev", "INTEGER", Rolling.GAUGE));
		ruleMap.put("1.3.6.1.4.1.6296.9.1.1.1.8", new Rule("1.3.6.1.4.1.6296.9.1.1.1.8", "dsCpuLoad5s", "INTEGER", Rolling.GAUGE));
		ruleMap.put("1.3.6.1.4.1.37288.1.1.3.1.1", new Rule("1.3.6.1.4.1.37288.1.1.3.1.1", "axgateCPU", "INTEGER", Rolling.GAUGE));
		
		PDUManager.setPDU(ruleMap.keySet());
		
		ruleMap.put("1.3.6.1.4.1.49447.1", new Rule("1.3.6.1.4.1.49447.1", "responseTime", "INTEGER", Rolling.GAUGE));
		ruleMap.put("1.3.6.1.4.1.49447.4", new Rule("1.3.6.1.4.1.49447.4", "cpu", "INTEGER", Rolling.GAUGE));
	}
	
	public H2Agent (Listener listener, Path path) throws Exception {
		System.out.println("***H2Agent v1.1***");
		
		System.out.format("Directory: %s\n", path.toString());
		
		nms = listener;
		root = path;
		
		connPool = JdbcConnectionPool.create(String.format("jdbc:h2:%s", path.resolve("nms").toString()), "sa", "");
		
		connPool.setMaxConnections(50);
		
		initTable();
		initData();
		
		batch = new Batch(path, resourceManager);
		
		batch.schedule(config.saveInterval);
		
		nodeManager = new NodeManager(this, config.requestInterval, config.timeout, config.retry);
		
		System.out.println("Agent start.");
	}
	
	@Override
	public void close() {
		synchronized(this.isClosed) {
			if (this.isClosed) {
				return;
			}
		}
		
		try {
			this.nodeManager.close();
		} catch (IOException ioe) {
			ioe.printStackTrace();
		}
		
		this.batch.cancel();
		
		this.connPool.dispose();
		
		System.out.println("Agent stop.");
	}
	
	private void initTable() throws SQLException {
		long start = System.currentTimeMillis();
		
		try (Connection c = connPool.getConnection()) {
			c.setAutoCommit(false);
			
			try {
				/**
				 * BODY
				 */
				try (Statement stmt = c.createStatement()) {
					stmt.executeUpdate("CREATE TABLE IF NOT EXISTS t_body"+
						" (maker VARCHAR NOT NULL"+
						", name VARCHAR NOT NULL"+
						", unit INT NOT NULL DEFAULT 1"+
						", front VARCHAR NOT NULL"+
						", rear VARCHAR NOT NULL"+
						", UNIQUE(maker, name));");
				}
				/**END**/
				
				/**
				 * CONFIG
				 **/
				try (Statement stmt = c.createStatement()) {
					stmt.executeUpdate("CREATE TABLE IF NOT EXISTS t_config"+
						" (key VARCHAR NOT NULL"+
						", value VARCHAR NOT NULL"+
						", PRIMARY KEY(key));");
				}
				/**END**/
				
				/**
				 * BANDWIDTH
				 */				
				try (Statement stmt = c.createStatement()) {
					stmt.executeUpdate("CREATE TABLE IF NOT EXISTS t_bandwidth"+
						" (id BIGINT NOT NULL"+
						", _index VARCHAR NOT NULL"+
						", value VARCHAR NOT NULL"+
						", UNIQUE(id, _index));");
				}
				/**END**/
				
				/**
				 * CRITICAL
				 */
				try (Statement stmt = c.createStatement()) {
					stmt.executeUpdate("CREATE TABLE IF NOT EXISTS t_critical"+
						" (id BIGINT NOT NULL"+
						", oid VARCHAR NOT NULL"+
						", _index VARCHAR NOT NULL"+
						", _limit INT NOT NULL DEFAULT 0"+
						", critical BOOLEAN DEFAULT NULL"+
						", UNIQUE(id, oid, _index));");
				}
				/**END**/
				
				/**
				 * EVENT
				 */
				try (Statement stmt = c.createStatement()) {
					stmt.executeUpdate("CREATE TABLE IF NOT EXISTS t_event"+
						" (event_id BIGINT PRIMARY KEY"+
						", id BIGINT NOT NULL"+
						", timestamp BIGINT NOT NULL"+
						", origin VARCHAR NOT NULL"+
						", level INT NOT NULL"+
						", message VARCHAR NOT NULL"+
						", date BIGINT NOT NULL);");
				}
				
				try (Statement stmt = c.createStatement()) {
					stmt.executeUpdate("CREATE INDEX IF NOT EXISTS event_index ON t_event (date);");
				}
				/**END**/
				
				/**
				 * ICON
				 */
				try (Statement stmt = c.createStatement()) {
					stmt.executeUpdate("CREATE TABLE IF NOT EXISTS t_icon"+
						" (type VARCHAR PRIMARY KEY"+
						", _group VARCHAR NOT NULL"+
						", src VARCHAR NOT NULL"+
						", disabled VARCHAR NOT NULL"+
						", shutdown VARCHAR NOT NULL"+
						");");
				}
				/**END**/
				
				/**
				 * USER
				 */
				try (Statement stmt = c.createStatement()) {
					stmt.executeUpdate("CREATE TABLE IF NOT EXISTS t_user"+
						" (id VARCHAR PRIMARY KEY"+
						", name VARCHAR NOT NULL DEFAULT ''"+
						", email VARCHAR DEFAULT NULL"+
						", sms VARCHAR DEFAULT NULL"+
						", password VARCHAR DEFAULT NULL"+
						", level INT NOT NULL DEFAULT 0"+
						");");
				}
				
				/**END**/
				
				/**
				 * NODE
				 */
				try (Statement stmt = c.createStatement()) {
					stmt.executeUpdate("CREATE TABLE IF NOT EXISTS t_node"+
						" (id BIGINT PRIMARY KEY"+
						", name VARCHAR DEFAULT NULL"+
						", type VARCHAR DEFAULT NULL"+
						", ip VARCHAR DEFAULT NULL UNIQUE"+
						", _group VARCHAR NOT NULL DEFAULT 'node'"+
						", label VARCHAR DEFAULT NULL"+
						", manager VARCHAR DEFAULT NULL"+
						", extra VARCHAR DEFAULT NULL"+
						", CONSTRAINT FK_USR_NOD FOREIGN KEY(manager) REFERENCES t_user(id)"+
						");");
				}
				
				/**END**/

				/**
				 * BRANCH
				 **/
				try (Statement stmt = c.createStatement()) {
					stmt.executeUpdate("CREATE TABLE IF NOT EXISTS t_branch"+
						" (id BIGINT PRIMARY KEY"+
						", address VARCHAR NOT NULL"+
						", subaddr VARCHAR NOT NULL DEFAULT ''"+
						", phone VARCHAR NOT NULL DEFAULT ''"+
						", lat VARCHAR DEFAULT NULL"+
						", lng VARCHAR DEFAULT NULL"+
						", CONSTRAINT FK_NOD_BRN FOREIGN KEY(id) REFERENCES t_node(id) ON DELETE CASCADE"+
						");");
				}
				/**END**/

				/**
				 * FACILITY
				 **/
				try (Statement stmt = c.createStatement()) {
					stmt.executeUpdate("CREATE TABLE IF NOT EXISTS t_facility"+
						" (id INT PRIMARY KEY AUTO_INCREMENT"+
						", name VARCHAR NOT NULL"+
						", type VARCHAR NOT NULL"+
						", unit INT NOT NULL DEFAULT 0"+
						", image VARCHAR NOT NULL DEFAULT ''"+
						", width INT NOT NULL"+
						", height INT NOT NULL"+
						", depth INT NOT NULL"+
						", rotate INT NOT NULL DEFAULT 0);");
				}
				/**END**/
				
				/**
				 * RACK
				 **/
				try (Statement stmt = c.createStatement()) {
					stmt.executeUpdate("CREATE TABLE IF NOT EXISTS t_rack"+
						" (id INT PRIMARY KEY AUTO_INCREMENT"+
						", name VARCHAR NOT NULL"+
						", x INT NOT NULL DEFAULT 0"+
						", y INT NOT NULL DEFAULT 0"+
						", unit INT NOT NULL DEFAULT 42);");
				}
				/**END**/
				
				/**
				 * LOCATION
				 **/
				try (Statement stmt = c.createStatement()) {
					stmt.executeUpdate("CREATE TABLE IF NOT EXISTS t_location"+
						" (node BIGINT PRIMARY KEY"+
						", maker VARCHAR NOT NULL"+
						", model VARCHAR NOT NULL"+
						", rack INT NOT NULL"+
						", position INT NOT NULL"+
						", CONSTRAINT FK_RACK_LOC FOREIGN KEY(rack) REFERENCES t_facility(id) ON DELETE CASCADE"+
						", CONSTRAINT FK_NOD_LOC FOREIGN KEY(node) REFERENCES t_node(id) ON DELETE CASCADE"+
						");");
				}
				/**END**/
				
				/**
				 * PATH
				 */
				try (Statement stmt = c.createStatement()) {
					stmt.executeUpdate("CREATE TABLE IF NOT EXISTS t_path"+
						" (id BIGINT PRIMARY KEY AUTO_INCREMENT"+
						", node_from BIGINT NOT NULL"+
						", node_to BIGINT NOT NULL"+
						", type VARCHAR DEFAULT ''"+
						", color VARCHAR DEFAULT ''"+
						", size INT DEFAULT 0"+
						", UNIQUE(node_from, node_to)"+
						", CONSTRAINT FK_NOD_PTH_F FOREIGN KEY(node_from) REFERENCES t_node(id) ON DELETE CASCADE"+
						", CONSTRAINT FK_NOD_PTH_T FOREIGN KEY(node_to) REFERENCES t_node(id) ON DELETE CASCADE"+
						");");
				}
				/**END**/
				
				/**
				 * LINK
				 */
				try (Statement stmt = c.createStatement()) {
					stmt.executeUpdate("CREATE TABLE IF NOT EXISTS t_link"+
						" (id BIGINT PRIMARY KEY AUTO_INCREMENT"+
						", path BIGINT NOT NULL"+
						", index_from BIGINT DEFAULT NULL"+
						", index_from_name VARCHAR DEFAULT NULL"+
						", index_to BIGINT DEFAULT  NULL"+
						", index_to_name VARCHAR DEFAULT NULL"+
						", extra VARCHAR DEFAULT NULL"+
						", CONSTRAINT UQ_FROM UNIQUE(path, index_from)"+
						", CONSTRAINT UQ_TO UNIQUE(path, index_to)"+
						", CONSTRAINT FK_PATH_LNK FOREIGN KEY(path) REFERENCES t_path(id) ON DELETE CASCADE"+
						");");
				}
				/**END**/
				
				/**
				 * POSITION
				 */
				try (Statement stmt = c.createStatement()) {
					stmt.executeUpdate("CREATE TABLE IF NOT EXISTS t_position"+
						" (name VARCHAR PRIMARY KEY"+
						", position VARCHAR NOT NULL DEFAULT '{}');");
				}
				/**END**/
				
				/**
				 * PROFILE
				 */
				try (Statement stmt = c.createStatement()) {
					stmt.executeUpdate("CREATE TABLE IF NOT EXISTS t_profile"+
						" (name VARCHAR PRIMARY KEY"+
						", protocol VARCHAR NOT NULL DEFAULT 'udp'"+
						", port INT NOT NULL DEFAULT 161"+
						", version INT NOT NULL DEFAULT 1"+
						", security VARCHAR NOT NULL DEFAULT 'public'"+
						", level INT NOT NULL DEFAULT 0"+
						", auth_protocol VARCHAR DEFAULT NULL"+
						", auth_key VARCHAR DEFAULT NULL"+
						", priv_protocol VARCHAR DEFAULT NULL"+
						", priv_key VARCHAR DEFAULT NULL);");
				}
				/**END**/
				
				/**
				 * MONITOR
				 */
				try (Statement stmt = c.createStatement()) {
					stmt.executeUpdate("CREATE TABLE IF NOT EXISTS t_monitor"+
						" (id BIGINT PRIMARY KEY"+
						", protocol VARCHAR NOT NULL"+
						", status BOOLEAN NOT NULL DEFAULT TRUE"+
						", snmp INT NOT NULL DEFAULT 0"+
						", profile VARCHAR DEFAULT NULL"+   
						", CONSTRAINT FK_PRF_MON FOREIGN KEY(profile) REFERENCES t_profile(name)"+
						", CONSTRAINT FK_NOD_MON FOREIGN KEY(id) REFERENCES t_node(id) ON DELETE CASCADE"+
						");");
				}
				/**END**/
				
				/**
				 * SETTING
				 */
				try (Statement stmt = c.createStatement()) {
					stmt.executeUpdate("CREATE TABLE IF NOT EXISTS t_setting"+
						" (key VARCHAR PRIMARY KEY"+
						", value VARCHAR DEFAULT NULL);");
				}
				/**END**/
				
				/**
				 * MANAGER
				 */
				try (Statement stmt = c.createStatement()) {
					stmt.executeUpdate("CREATE TABLE IF NOT EXISTS t_manager"+
						" (user VARCHAR"+
						", node BIGINT"+
						", timestamp BIGINT NOT NULL"+
						", CONSTRAINT FK_NOD_MGR FOREIGN KEY(node) REFERENCES t_node(id) ON DELETE CASCADE"+
						");");
				}
				/**END**/
				
				c.commit();
			} catch (Exception e) {
				c.rollback();
				
				throw e;
			}
		}
		
		System.out.format("DB Table initialized in %dms.\n", System.currentTimeMillis() - start);
	}
	
	private void initData() throws SQLException {
		long start = System.currentTimeMillis();

		try (Connection c = connPool.getConnection()) {
			try (Statement stmt = c.createStatement()) {
				try (ResultSet rs = stmt.executeQuery("SELECT"+
					" COALESCE(MAX(event_id), 0)"+
					" FROM t_event;")) {
					if (rs.next()) {
						nextEventID = rs.getLong(1) +1;
					}
				}
			}
			
			try (Statement stmt = c.createStatement()) {
				try (ResultSet rs = stmt.executeQuery("SELECT COUNT(id) FROM t_user;")) {
					if (!rs.next() || rs.getLong(1) == 0) {
						try (PreparedStatement pstmt = c.prepareStatement("INSERT INTO t_user"+
							" (id, password, level)"+
							" VALUES ('root', '63a9f0ea7bb98050796b649e85481845', 0);")) {
							
							pstmt.executeUpdate();
						}
					}
				}
			}
			
			try (Statement stmt = c.createStatement()) {
				try (ResultSet rs = stmt.executeQuery("SELECT"+
					" id, oid, _index, _limit, critical"+
					" FROM t_critical;")) {
					Value v;
					while (rs.next()) {
						v = resourceManager.getValue(rs.getLong(1), rs.getString(2), rs.getString(3), true);
						
						v.limit = rs.getInt(4);
						
						rs.getBoolean(5);
						
						v.critical = rs.wasNull()? false: true;
					}
				}
			}
			
			try (Statement stmt = c.createStatement()) {
				try (ResultSet rs = stmt.executeQuery("SELECT"+
					" key, value"+
					" FROM t_config;")) {
					while (rs.next()) {
						switch (rs.getString(1)) {
						case "ifMon":
							this.config.ifMon = Boolean.valueOf(rs.getString(rs.getString(2)));
							
							break;
						case "requestInterval":
							this.config.requestInterval = Long.valueOf(rs.getString(2));
							
							break;
						case "retry":
							this.config.retry = Integer.valueOf(rs.getString(2));

							break;
						case "saveInterval":
							this.config.saveInterval = Long.valueOf(rs.getString(2));

							break;
						case "storeDate":
							this.config.storeDate = Long.valueOf(rs.getString(2));

							break;
						case "smtpEnable":
							this.config.smtpEnable = Boolean.valueOf(rs.getString(2));
							
							break;
						case "smtpServer":
							this.config.smtpServer = rs.getString(2);
							
							break;
						case "smtpProtocol":
							this.config.smtpProtocol = rs.getString(2);
							
							break;
						case "smtpUser":
							this.config.smtpUser = rs.getString(2);
							
							break;
						case "smtpPassword":
							this.config.smtpPassword = rs.getString(2);
							
							break;
						case "syslog":
							this.config.syslog = rs.getString(2);
							
							break;
						case "timeout":
							this.config.timeout = Integer.valueOf(rs.getString(2));

							break;
						}
					}
				}
			}
			
			try (Statement stmt = c.createStatement()) {
				try (ResultSet rs = stmt.executeQuery("SELECT"+
					" COUNT(name)"+
					" FROM t_profile;")) {
					if (!rs.next() || rs.getLong(1) == 0) {
						try (PreparedStatement pstmt = c.prepareStatement("INSERT"+
							" INTO t_profile (name, security)"+
							" VALUES ('public', 'public');")) {
							pstmt.executeUpdate();
						}
					}
				}
			}
			
			try (Statement stmt = c.createStatement()) {
				try (ResultSet rs = stmt.executeQuery("SELECT"+
					" COALESCE(MAX(id), 0)"+
					" FROM t_node")) {
					if (rs.next()) {
						nextNodeID = rs.getLong(1) +1;
					}
				}
			}
		}
		
		System.out.format("Database parsed in %dms.\n", System.currentTimeMillis() - start);
	}

	@Override
	public boolean addBody(JSONObject body) {
		long ttt = System.currentTimeMillis();
		try (Connection c = this.connPool.getConnection()) {
			try (PreparedStatement pstmt = c.prepareStatement("INSERT"+
				" INTO t_body"+
				" (maker, name, unit, front, rear)"+
				" VALUES(?, ?, ?, ?, ?);")) {
				pstmt.setString(1, body.getString("maker"));
				pstmt.setString(2, body.getString("name"));
				pstmt.setInt(3, body.getInt("unit"));
				pstmt.setString(4, body.getString("front"));
				pstmt.setString(5, body.getString("rear"));
				
				pstmt.executeUpdate();
			}
			
			return true;
		} catch (SQLException sqle) {
			sqle.printStackTrace();
		} finally {
			if (System.currentTimeMillis() - ttt > 30000) {
				new Exception().printStackTrace();
			}
		}
		
		return false;
	}
	
	@Override
	public boolean addBranch(JSONObject branch) {
		long ttt = System.currentTimeMillis();
		try (Connection c = this.connPool.getConnection()) {
			try (PreparedStatement pstmt = c.prepareStatement("INSERT INTO"+
				" t_branch"+
				" (parent, name, address, subaddr, phone, lat, lng)"+
				" VALUES(?, ?, ?, ?, ?, ?, ?);")) {
				pstmt.setLong(1, branch.getLong("parent"));
				pstmt.setString(2, branch.getString("name"));
				pstmt.setString(3, branch.getString("address"));
				pstmt.setString(4, branch.getString("subaddr"));
				pstmt.setString(5, branch.getString("phone"));
				
				if (branch.has("lat")) {
					pstmt.setString(6, branch.getString("lat"));
				} else {
					pstmt.setNull(6, Types.NULL);
				}
				
				if (branch.has("lng")) {
					pstmt.setString(7, branch.getString("lng"));
				} else {
					pstmt.setNull(7, Types.NULL);
				}
				
				pstmt.executeUpdate();
			}
			
			return true;
		} catch (SQLException sqle) {
			sqle.printStackTrace();
		} finally {
			if (System.currentTimeMillis() - ttt > 30000) {
				new Exception().printStackTrace();
			}
		}
		
		return false;
	}
	
	@Override
	public boolean addFacility(JSONObject facility) throws SQLException {
		long ttt = System.currentTimeMillis();
		try (Connection c = this.connPool.getConnection()) {
			try (PreparedStatement pstmt = c.prepareStatement("INSERT INTO t_facility"+
				" (name, type, width, height, depth, rotate, unit, image)"+
				" VALUES(?, ?, ?, ?, ?, ?, ?, ?);")) {
				pstmt.setString(1, facility.getString("name"));
				pstmt.setString(2, facility.getString("type"));
				pstmt.setInt(3, facility.getInt("width"));
				pstmt.setInt(4, facility.getInt("height"));
				pstmt.setInt(5, facility.getInt("depth"));
				pstmt.setInt(6, facility.getInt("rotate"));
				pstmt.setInt(7, facility.getInt("unit"));
				pstmt.setString(8, facility.getString("image"));
				
				pstmt.executeUpdate();
			}
			
			return true;
		} finally {
			if (System.currentTimeMillis() - ttt > 30000) {
				new Exception().printStackTrace();
			}
		}
	}
	
	@Override
	public JSONObject addIcon(String type, JSONObject icon) {
		long ttt = System.currentTimeMillis();
		try (Connection c = this.connPool.getConnection()) {
			try (PreparedStatement pstmt = c.prepareStatement("INSERT INTO t_icon"+
				" (type, _group, src, disabled, shutdown)"+
				" VALUES (?, ?, ?, ?, ?);")) {
				pstmt.setString(1, icon.getString("type"));
				pstmt.setString(2, icon.getString("group"));
				pstmt.setString(3, icon.getString("src"));
				pstmt.setString(4, icon.getString("disabled"));
				pstmt.setString(5, icon.getString("shutdown"));
				
				pstmt.executeUpdate();
			}
		
			return icon;
		} catch (SQLException sqle) {
			sqle.printStackTrace();
		} finally {
			if (System.currentTimeMillis() - ttt > 30000) {
				new Exception().printStackTrace();
			}
		}
		
		return null;
	}

	@Override
	public boolean addLink(long path) {
		long ttt = System.currentTimeMillis();
		try (Connection c = this.connPool.getConnection()) {			
			try (PreparedStatement pstmt = c.prepareStatement("INSERT INTO t_link (path) values (?);")) {
				pstmt.setLong(1, path);
				
				pstmt.executeUpdate();
			}
			
			return true;
		} catch (SQLException sqle) {
			sqle.printStackTrace();
		} finally {
			if (System.currentTimeMillis() - ttt > 30000) {
				new Exception().printStackTrace();
			}
		}
		
		return false;
	}

	@Override
	public JSONObject addNode(JSONObject node) {
		long ttt = System.currentTimeMillis();
		try (Connection c = this.connPool.getConnection()) {
			c.setAutoCommit(false);
			
			try {
				synchronized(this.nextNodeID) {
					try (PreparedStatement pstmt = c.prepareStatement("INSERT INTO t_node"+
						" (id, name, type, ip, label, extra, _group)"+
						" values (?, ?, ?, ?, ?, ?, ?);")) {
						pstmt.setLong(1, this.nextNodeID);
						
						if (node.has("name")) {
							pstmt.setString(2, node.getString("name"));
						}
						else {
							pstmt.setNull(2, Types.NULL);
						}
						
						if (node.has("type")) {
							pstmt.setString(3, node.getString("type"));
						}
						else {
							pstmt.setNull(3, Types.NULL);
						}
						
						if (node.has("ip")) {
							pstmt.setString(4, node.getString("ip"));
						}
						else {
							pstmt.setNull(4, Types.NULL);
						}
						
						if (node.has("label")) {
							pstmt.setString(5, node.getString("label"));
						}
						else {
							pstmt.setNull(5, Types.NULL);
						}
						
						if (node.has("extra")) {
							pstmt.setString(6, node.getString("extra"));
						}
						else {
							pstmt.setNull(6, Types.NULL);
						}
						
						pstmt.setString(7, node.getString("group"));
						
						pstmt.executeUpdate();
					}
					
					if (node.has("branch")) {
						JSONObject branch = node.getJSONObject("branch");
						
						try (PreparedStatement pstmt = c.prepareStatement("INSERT INTO"+
							" t_branch"+
							" (id, address, subaddr, phone, lat, lng)"+
							" values (?, ?, ?, ?, ?, ?);")) {
							pstmt.setLong(1, this.nextNodeID);
							pstmt.setString(2, branch.getString("address"));
							pstmt.setString(3, branch.getString("subaddr"));
							pstmt.setString(4, branch.getString("phone"));
							
							if (branch.has("lat")) {
								pstmt.setString(5, branch.getString("lat"));
							} else {
								pstmt.setNull(5, Types.NULL);
							}
							
							if (branch.has("lng")) {
								pstmt.setString(6, branch.getString("lng"));
							} else {
								pstmt.setNull(6, Types.NULL);
							}
							
							pstmt.executeUpdate();
						}
					}
				
					node.put("id", this.nextNodeID);
				
					c.commit();
					
					this.nextNodeID++;
					
					return node;
				}
			} catch (SQLException sqle) {
				c.rollback();
				
				throw sqle;
			}
		} catch (SQLException sqle) {
			sqle.printStackTrace();
		} finally {
			if (System.currentTimeMillis() - ttt > 30000) {
				new Exception().printStackTrace();
			}
		}
		
		return null;
	}

	@Override
	public boolean addPath(long nodeFrom, long nodeTo) {
		if (nodeFrom >= nodeTo) {
			return false;
		}
		
		long ttt = System.currentTimeMillis();
		try (Connection c = this.connPool.getConnection()) {
			try (PreparedStatement pstmt = c.prepareStatement("INSERT"+
				" INTO t_path"+
				" (node_from, node_to)"+
				" values (?, ?);")) {
				pstmt.setLong(1, nodeFrom);
				pstmt.setLong(2, nodeTo);
				
				pstmt.executeUpdate();
			}
			
			return true;
		} catch (SQLException sqle) {
			sqle.printStackTrace();
		} finally {
			if (System.currentTimeMillis() - ttt > 30000) {
				new Exception().printStackTrace();
			}
		}
		
		return false;
	}

	@Override
	public boolean addProfile(String name, JSONObject profile) {
		long ttt = System.currentTimeMillis();
		try (Connection c = this.connPool.getConnection()) {
			try (PreparedStatement pstmt = c.prepareStatement("INSERT"+
				" INTO t_profile"+
				" (name, protocol, port, version, security, level, auth_protocol, auth_key, priv_protocol, priv_key)"+
				" VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?);")) {
				pstmt.setString(1, profile.getString("name"));
				pstmt.setString(2, profile.getString("protocol"));
				pstmt.setInt(3, profile.getInt("port"));
				pstmt.setInt(4, profile.getInt("version"));
				pstmt.setString(5, profile.getString("security"));
				pstmt.setInt(6, profile.getInt("level"));
				pstmt.setString(7, profile.has("authProtocol")? profile.getString("authProtocol"): null);
				pstmt.setString(8, profile.has("authKey")? profile.getString("authKey"): null);
				pstmt.setString(9, profile.has("privProtocol")? profile.getString("privProtocol"): null);
				pstmt.setString(10, profile.has("privKey")? profile.getString("privKey"): null);
				
				pstmt.executeUpdate();
			}
			
			return true;
		} catch (SQLException sqle) {
			sqle.printStackTrace();
		} finally {
			if (System.currentTimeMillis() - ttt > 30000) {
				new Exception().printStackTrace();
			}
		}
		
		return false;
	}

	@Override
	public boolean addRack(JSONObject rack) {
		long ttt = System.currentTimeMillis();
		try (Connection c = this.connPool.getConnection()) {
			try (PreparedStatement pstmt = c.prepareStatement("INSERT INTO t_rack"+
				" (name, unit)"+
				" VALUES(?, ?);")) {
				pstmt.setString(1, rack.getString("name"));
				pstmt.setInt(2, rack.getInt("unit"));
				
				pstmt.executeUpdate();
			}
			
			return true;
		} catch (SQLException sqle) {
			sqle.printStackTrace();
		} finally {
			if (System.currentTimeMillis() - ttt > 30000) {
				new Exception().printStackTrace();
			}
		}
		
		return false;
	}
	
	@Override
	public boolean addUser(String id, JSONObject user) throws SQLException {
		long ttt = System.currentTimeMillis();
		try (Connection c = this.connPool.getConnection()) {
			try (PreparedStatement pstmt = c.prepareStatement("SELECT"+
				" id"+
				" FROM t_user"+
				" WHERE id=?")) {
				pstmt.setString(1, id);
				
				try (ResultSet rs = pstmt.executeQuery()) {
					if (rs.next()) {
						return false;
					}
				}
			}
			
			try (PreparedStatement pstmt = c.prepareStatement("INSERT INTO"+
				" t_user (id, name, email, sms, level)"+
				" VALUES(?, ?, ?, ?, ?);")) {
				pstmt.setString(1, id);
				pstmt.setString(2, user.getString("name"));	
				
				if (user.has("email")) {
					pstmt.setString(3, user.getString("email"));	
				} else {
					pstmt.setNull(3, Types.NULL);
				}
				
				if (user.has("sms")) {
					pstmt.setString(4, user.getString("sms"));	
				} else {
					pstmt.setNull(4, Types.NULL);
				}
				
				pstmt.setInt(5, 2);
				
				pstmt.executeUpdate();
				
				return true;
			}
		} finally {
			if (System.currentTimeMillis() - ttt > 30000) {
				new Exception().printStackTrace();
			}
		}
	}

	@Override
	public void backup() throws Exception {
		long ttt = System.currentTimeMillis();
		try (Connection c = this.connPool.getConnection()) {
			try (Statement stmt = c.createStatement()) {
				stmt.executeUpdate(String.format("BACKUP TO '%s';", this.root.resolve("backup.zip")));
			}
		} finally {
			if (System.currentTimeMillis() - ttt > 30000) {
				new Exception().printStackTrace();
			}
		}
	}
	
	@Override
	public JSONObject getConfig() {		
		return this.config.getJSONObject();
	}

	@Override
	public JSONObject getBranch() {
		long ttt = System.currentTimeMillis();
		try (Connection c = this.connPool.getConnection()) {
			try (Statement stmt = c.createStatement()) {
				JSONObject
					branchData = new JSONObject(),
					branch;
				String latLng;
				
				try (ResultSet rs = stmt.executeQuery("SELECT"+
					" B.id, name, address, subaddr, phone, lat, lng"+
					" FROM t_branch AS B"+
					" LEFT JOIN t_node AS N"+
					" ON B.id = N.id;")) {
					while (rs.next()) {
						branch = new JSONObject()
							.put("id", rs.getLong(1))
							.put("name", rs.getString(2))
							.put("address", rs.getString(3))
							.put("subaddr", rs.getString(4))
							.put("phone", rs.getString(5));
						
						latLng = rs.getString(6);
						
						if (!rs.wasNull()) {
							branch.put("lat", latLng);
						}
						
						latLng = rs.getString(7);
						
						if (!rs.wasNull()) {
							branch.put("lng", latLng);
						}
						
						branchData.put(Long.toString(rs.getLong(1)), branch);
					}
				}
				
				return branchData;
			}
		} catch (SQLException sqle) {
			sqle.printStackTrace();
		} finally {
			if (System.currentTimeMillis() - ttt > 30000) {
				new Exception().printStackTrace();
			}
		}
		
		return null;
	}
	
	@Override
	public JSONObject getBody() throws SQLException {
		long ttt = System.currentTimeMillis();
		try (Connection c = this.connPool.getConnection()) {
			try (Statement stmt = c.createStatement()) {
				
				try (ResultSet rs = stmt.executeQuery("SELECT"+
						" id, maker, name, unit, front, rear"+
						" FROM t_body;")) {
					JSONObject bodyData = new JSONObject();
					
					while (rs.next()) {
						bodyData.put(Long.toString(rs.getLong(1)), new JSONObject()
							.put("id", rs.getLong(1))
							.put("maker", rs.getString(2))
							.put("name", rs.getString(3))
							.put("unit", rs.getInt(4))
							.put("front", rs.getString(5))
							.put("rear", rs.getString(6)));
					}
					
					return bodyData;
				}
			}
		} finally {
			if (System.currentTimeMillis() - ttt > 30000) {
				new Exception().printStackTrace();
			}
		}
	}
	
	@Override
	public JSONObject getBody(long id) throws SQLException {
		long ttt = System.currentTimeMillis();
		try (Connection c = this.connPool.getConnection()) {
			try (PreparedStatement pstmt = c.prepareStatement("SELECT"+
				" maker, name, unit, front, rear"+
				" FROM t_body"+
				" WHERE id=?;")) {
				pstmt.setLong(1, id);
				
				try (ResultSet rs = pstmt.executeQuery()) {
					if (rs.next()) {
						return new JSONObject()
							.put("id", id)
							.put("maker", rs.getString(1))
							.put("name", rs.getString(2))
							.put("unit", rs.getInt(3))
							.put("front", rs.getString(4))
							.put("rear", rs.getString(5));
					} else {
						return null;
					}
				}
			}
		} finally {
			if (System.currentTimeMillis() - ttt > 30000) {
				new Exception().printStackTrace();
			}
		}
	}
	
	@Override
	public JSONObject getBranch(long id) throws SQLException {
		long ttt = System.currentTimeMillis();
		try (Connection c = this.connPool.getConnection()) {
			try (PreparedStatement pstmt = c.prepareStatement("SELECT"+
				" name, address, subaddr, phone, lat, lng"+
				" FROM t_branch AS B"+
				" LEFT JOIN node AS N"+
				" ON B.id=N.id"+
				" WHERE B.id=?;")) {
				pstmt.setLong(1, id);
				
				try (ResultSet rs = pstmt.executeQuery()) {
					if (rs.next()) {
						JSONObject branch = new JSONObject();
						String latLng;
						
						branch
							.put("id", id)
							.put("name", rs.getString(1))
							.put("address", rs.getString(2))
							.put("subaddr", rs.getString(3))
							.put("phone", rs.getString(4));
						
						latLng = rs.getString(5);
						
						if (!rs.wasNull()) {
							branch.put("lat", latLng);
						}
						
						latLng = rs.getString(6);
						
						if (!rs.wasNull()) {
							branch.put("lng", latLng);
						}
						
						return branch;
					} else {
						return null;
					}
				}
			}
		} finally {
			if (System.currentTimeMillis() - ttt > 30000) {
				new Exception().printStackTrace();
			}
		}
	}
	
	@Override
	public JSONObject getEvent(long eventID) throws SQLException {
		long ttt = System.currentTimeMillis();
		try (Connection c = this.connPool.getConnection()) {
			try (PreparedStatement pstmt = c.prepareStatement("SELECT"+
				" E.id, timestamp, origin, level, message, name, ip"+
				" FROM t_event AS E"+
				" LEFT JOIN t_node AS N"+
				" ON E.id=N.id"+
				" WHERE event_id=?;")) {
				pstmt.setLong(1, eventID);
				
				try (ResultSet rs = pstmt.executeQuery()) {
					if (rs.next()) {
						JSONObject event = new JSONObject();
						String value;
						
						event.put("eventID", eventID)
							.put("id", rs.getLong(1))
							.put("timestamp", rs.getLong(2))
							.put("origin", rs.getString(3))
							.put("level", rs.getInt(4))
							.put("message", rs.getString(5));
						
						
						value = rs.getString(6);
						
						if (!rs.wasNull()) {
							event.put("name", value);
						}
						
						value = rs.getString(7);
						
						if (!rs.wasNull()) {
							event.put("ip", value);
						}
						
						return event;
					} else {
						return null;
					}
				}
			} 
		} finally {
			if (System.currentTimeMillis() - ttt > 30000) {
				new Exception().printStackTrace();
			}
		}
	}

	@Override
	public JSONObject getEvent(JSONObject search) throws SQLException {
		long
			start = search.getLong("start"),
			end = search.getLong("end");
		
		String keyword = search.has("keyword")?
			String.format("(name ILIKE '%%%s%%' OR ip ILIKE '%%%s%%')", search.getString("keyword"), search.getString("keyword")):
			"TRUE";
		
		ArrayList<String> al = new ArrayList<>();
		
		if (search.has("shutdown")) {
			al.add("2");
		}
		
		if (search.has("critical")) {
			al.add("1");
		}
		
		if (search.has("normal")) {
			al.add("0");
		}
		
		String level = al.size() == 3?
			"TRUE": String.format("level IN(%s)", String.join(",", al));
		
		long ttt = System.currentTimeMillis();
		try (Connection c = this.connPool.getConnection()) {
			try (PreparedStatement pstmt = c.prepareStatement(
				String.format(
					"SELECT"+
					" E.id, timestamp, origin, level, message, event_id, name, ip"+
					" FROM t_event AS E"+
					" LEFT JOIN t_node AS N"+
					" ON E.id=N.id"+
					" WHERE date>=? AND date<=? AND %s AND %s;", level, keyword))) {
				pstmt.setLong(1, start);
				pstmt.setLong(2, end);
				
				try (ResultSet rs = pstmt.executeQuery()) {
					JSONObject
						eventData = new JSONObject(),
						event;
					String value;
					
					while(rs.next()) {
						event = new JSONObject()
							.put("id", rs.getLong(1))
							.put("timestamp", rs.getLong(2))
							.put("origin", rs.getString(3))
							.put("level", rs.getInt(4))
							.put("message", rs.getString(5))
							.put("eventID", rs.getLong(6));
						
						value = rs.getString(7);
						
						if (!rs.wasNull()) {
							event.put("name", value);
						}
						
						value = rs.getString(8);
						
						if (!rs.wasNull()) {
							event.put("ip", value);
						}
						
						eventData.put(Long.toString(rs.getLong(6)), event);
					}
					
					return eventData;
				}
			} 
		} finally {
			if (System.currentTimeMillis() - ttt > 30000) {
				new Exception().printStackTrace();
			}
		}
	}
	
	@Override
	public JSONObject getEventByDate(long date) throws SQLException {
		Calendar calendar = Calendar.getInstance();
		
		calendar.setTimeInMillis(date);
		
		calendar.set(Calendar.HOUR_OF_DAY, 0);
		calendar.set(Calendar.MINUTE, 0);
		calendar.set(Calendar.SECOND, 0);
		calendar.set(Calendar.MILLISECOND, 0);
	
		long ttt = System.currentTimeMillis();
		try (Connection c = this.connPool.getConnection()) {
			try (PreparedStatement pstmt = c.prepareStatement("SELECT"+
				" E.id, timestamp, origin, level, message, event_id, name, ip"+
				" FROM t_event AS E"+
				" LEFT JOIN t_node AS N"+
				" ON E.id=N.id"+
				" WHERE date=?;")) {
				pstmt.setLong(1, calendar.getTimeInMillis());
				
				try (ResultSet rs = pstmt.executeQuery()) {
					JSONObject
						eventData = new JSONObject(),
						event;
					String value;
					
					while(rs.next()) {
						event = new JSONObject()
							.put("id", rs.getLong(1))
							.put("timestamp", rs.getLong(2))
							.put("origin", rs.getString(3))
							.put("level", rs.getInt(4))
							.put("message", rs.getString(5))
							.put("eventID", rs.getLong(6));

						value = rs.getString(7);
						
						if (!rs.wasNull()) {
							event.put("name", value);
						}
						
						value = rs.getString(8);
						
						if (!rs.wasNull()) {
							event.put("ip", value);
						}
						
						eventData.put(Long.toString(rs.getLong(6)), event);
					}
					
					return eventData;
				}
			} 
		} finally {
			if (System.currentTimeMillis() - ttt > 30000) {
				new Exception().printStackTrace();
			}
		}
	}

	@Override
	public JSONObject getFacility(long id) throws SQLException {
		long ttt = System.currentTimeMillis();
		try (Connection c = this.connPool.getConnection()) {
			try (PreparedStatement pstmt = c.prepareStatement("SELECT"+
				" name, type, unit, image, width, height, depth, rotate"+
				" FROM t_facility"+
				" WHERE id=?;")) {
				pstmt.setLong(1, id);
				
				try (ResultSet rs = pstmt.executeQuery()) {
					if (rs.next()) {
						return new JSONObject()
							.put("id", id)
							.put("name", rs.getString(1))
							.put("type", rs.getString(2))
							.put("unit", rs.getInt(3))
							.put("image", rs.getString(4))
							.put("width", rs.getInt(5))
							.put("height", rs.getInt(6))
							.put("depth", rs.getInt(7))
							.put("rotate", rs.getInt(8));
					}
				}
			}
		} finally {
			if (System.currentTimeMillis() - ttt > 30000) {
				new Exception().printStackTrace();
			}
		}
		
		return null;
	}
	
	@Override
	public JSONObject getFacility() throws SQLException {
		try (Connection c = this.connPool.getConnection()) {
			try (Statement stmt = c.createStatement()) {
				try (ResultSet rs = stmt.executeQuery("SELECT"+
					" id, name, type, unit, image, width, height, depth, rotate"+
					" FROM t_facility;")) {
					JSONObject facilityData = new JSONObject();
					
					while (rs.next()) {
						facilityData.put(Long.toString(rs.getLong(1)), new JSONObject()
							.put("id", rs.getLong(1))
							.put("name", rs.getString(2))
							.put("type", rs.getString(3))
							.put("unit", rs.getInt(4))
							.put("image", rs.getString(5))
							.put("width", rs.getInt(6))
							.put("height", rs.getInt(7))
							.put("depth", rs.getInt(8))
							.put("rotate", rs.getInt(9)));
					}
					
					return facilityData;
				}
			}
		}
	}
	
	@Override
	public JSONObject getIcon() throws SQLException {
		long ttt = System.currentTimeMillis();
		try (Connection c = this.connPool.getConnection()) {
			try (Statement stmt = c.createStatement()) {
				JSONObject iconData = new JSONObject();
				
				try (ResultSet rs = stmt.executeQuery("SELECT type, _group, src, disabled, shutdown"+
					" FROM t_icon;")) {
					while (rs.next()) {
						iconData.put(rs.getString(1), new JSONObject()
							.put("type", rs.getString(1))
							.put("group", rs.getString(2))
							.put("src", rs.getString(3))
							.put("disabled", rs.getString(4))
							.put("shutdown", rs.getString(5)));
					}
				}
				
				return iconData;
			}
		} finally {
			if (System.currentTimeMillis() - ttt > 30000) {
				new Exception().printStackTrace();
			}
		}
	}

	@Override
	public JSONObject getIcon(String type) throws SQLException {
		long ttt = System.currentTimeMillis();
		try (Connection c = this.connPool.getConnection()) {
			try (PreparedStatement pstmt = c.prepareStatement("SELECT type, _group, src, disabled, shutdown"+
				" FROM t_icon"+
				" WHERE type=?;")) {
				pstmt.setString(1,  type);
				
				try (ResultSet rs = pstmt.executeQuery()) {
					if (rs.next()) {
						return new JSONObject()
							.put("type", rs.getString(1))
							.put("group", rs.getString(2))
							.put("src", rs.getString(3))
							.put("disabled", rs.getString(4))
							.put("shutdown", rs.getString(5));
					} else {
						return null;
					}
				}
			}
		} finally {
			if (System.currentTimeMillis() - ttt > 30000) {
				new Exception().printStackTrace();
			}
		}
	}

	@Override
	public JSONObject getInformation() {
		Calendar c = Calendar.getInstance();
		JSONObject body = new JSONObject();
		
		c.set(Calendar.DATE, c.get(Calendar.DATE) -1);
		
		try {
			body.put("usage", Files.size(this.root.resolve(String.format("%04d-%02d-%02d.mv.db",
					c.get(Calendar.YEAR),
					c.get(Calendar.MONTH) +1,
					c.get(Calendar.DAY_OF_MONTH)))));
			} catch (Exception e) {}
		
		body.put("maxConnection", this.connPool.getActiveConnections());
		
		return body;
	}

	@Override
	public JSONObject getLimit() throws SQLException {
		long ttt = System.currentTimeMillis();
		try (Connection c = this.connPool.getConnection()) {
			try (Statement stmt = c.createStatement()) {
				try (ResultSet rs = stmt.executeQuery("SELECT"+
					" id, oid, _index, _limit"+
					" FROM t_critical"+
					";")) {
					JSONArray criticals = new JSONArray();
					
					while (rs.next()) {
						criticals.put(new JSONObject()
							.put("id", rs.getLong(1))
							.put("oid", rs.getString(2))
							.put("index", rs.getString(3))
							.put("limit", rs.getInt(4)));
					}
					
					return new JSONObject().put("criticals", criticals);
				}
			}
		} finally {
			if (System.currentTimeMillis() - ttt > 30000) {
				new Exception().printStackTrace();
			}
		}
	}
	
	@Override
	public JSONObject getLimit(long id, String oid, String index) {
		Value value = resourceManager.getValue(id, oid, index);
		
		return new JSONObject()
			.put("limit", value == null? 0: value.limit);
	}
	
	@Override
	public JSONObject getLink() throws SQLException {
		long ttt = System.currentTimeMillis();
		try (Connection c = this.connPool.getConnection()) {
			try (Statement stmt = c.createStatement()) {
				JSONObject
					linkData = new JSONObject(),
					link;
				long id;
				String extra;
				
				try (ResultSet rs = stmt.executeQuery("SELECT"+
					" L.id, node_from, index_from, index_from_name, node_to, index_to, index_to_name, extra"+
					" FROM t_link AS L"+
					" LEFT JOIN t_path AS P"+
					" WHERE path=P.id"+
					";")) {
					while (rs.next()) {
						link = new JSONObject();
						
						link.put("nodeFrom", rs.getLong(2));
						
						id = rs.getLong(3);
						
						if (!rs.wasNull()) {
							link.put("indexFrom", id);
							link.put("indexFromName", rs.getString(4));
						}
						
						link.put("nodeTo", rs.getLong(5));
						
						id = rs.getLong(6);
						
						if (!rs.wasNull()) {
							link.put("indexTo", id);
							link.put("indexToName", rs.getString(7));
						}
						
						extra = rs.getString(8);
						
						if (!rs.wasNull()) {
							link.put("extra", new JSONObject(extra));
						}
						
						id = rs.getLong(1);
						
						linkData.put(Long.toString(id), link
							.put("id", id));
					}
				}
				
				return linkData;
			}
		} finally {
			if (System.currentTimeMillis() - ttt > 30000) {
				new Exception().printStackTrace();
			}
		}
	}

	@Override
	public JSONObject getLink(long path) throws SQLException {
		long ttt = System.currentTimeMillis();
		try (Connection c = this.connPool.getConnection()) {
			try (PreparedStatement pstmt = c.prepareStatement("SELECT"+
				" id, path, index_from, index_from_name, index_to, index_to_name, extra"+
				" FROM t_link"+
				" WHERE path=?;")) {
				pstmt.setLong(1,  path);
				
				JSONObject
					linkData = new JSONObject(),
					link;
				long index;
				
				try (ResultSet rs = pstmt.executeQuery()) {
					while (rs.next()) {
						link = new JSONObject()
							.put("id", rs.getLong(1))
							.put("path", rs.getLong(2));
						
						index = rs.getLong(3);
						
						if (!rs.wasNull()) {
							link.put("indexFrom", index);
							link.put("indexFromName", rs.getString(4));
						}
						
						index = rs.getLong(5);
						
						if (!rs.wasNull()) {
							link.put("indexTo", index);
							link.put("indexToName", rs.getString(6));
						}
						
						if (rs.getString(7) != null) {
							link.put("extra", new JSONObject(rs.getString(7)));
						}
						
						linkData.put(Long.toString(rs.getLong(1)), link);
					}
				}
				
				return linkData;
			}
		} finally {
			if (System.currentTimeMillis() - ttt > 30000) {
				new Exception().printStackTrace();
			}
		}
	}

	@Override
	public JSONObject getLocation() throws SQLException {
		long ttt = System.currentTimeMillis();
		try (Connection c = this.connPool.getConnection()) {
			try (Statement stmt = c.createStatement()) {
				try (ResultSet rs = stmt.executeQuery("SELECT"+
					" node, maker, L.model, rack, position, N.name, ip"+
					" FROM t_location AS L"+
					" LEFT JOIN t_node AS N"+
					" ON node=N.id;")) {
					JSONObject locationData = new JSONObject();
					JSONObject location;
					String value;
					
					while (rs.next()) {
						location = new JSONObject()
							.put("node", rs.getLong(1))
							.put("maker", rs.getString(2))
							.put("model", rs.getString(3))
							.put("rack", rs.getInt(4))
							.put("position", rs.getInt(5));
						
						value = rs.getString(6);
						
						if (!rs.wasNull()) {
							location.put("node_name", value);
						}
						
						value = rs.getString(7);
						
						if (!rs.wasNull()) {
							location.put("node_ip", value);
						}
						
						locationData.put(Long.toString(rs.getLong(1)), location);
					}
					
					return locationData;
				}
			}
		} finally {
			if (System.currentTimeMillis() - ttt > 30000) {
				new Exception().printStackTrace();
			}
		}
	}
	
	@Override
	public JSONObject getLocation(long rack) throws SQLException {
		long ttt = System.currentTimeMillis();
		try (Connection c = this.connPool.getConnection()) {
			try (PreparedStatement pstmt = c.prepareStatement("SELECT"+
				" node, maker, L.model, rack, position, N.name, ip"+
				" FROM t_location AS L"+
				" LEFT JOIN t_node AS N"+
				" ON node=N.id"+
				" WHERE rack=?;")) {
				pstmt.setLong(1, rack);
				
				try (ResultSet rs = pstmt.executeQuery()) {
					JSONObject locationData = new JSONObject();
					JSONObject location;
					String value;
					
					while (rs.next()) {
						location = new JSONObject()
							.put("node", rs.getLong(1))
							.put("maker", rs.getString(2))
							.put("model", rs.getString(3))
							.put("rack", rs.getInt(4))
							.put("position", rs.getInt(5));
						
						value = rs.getString(6);
						
						if (!rs.wasNull()) {
							location.put("name", value);
						} else {
							value = rs.getString(7);
							
							if (!rs.wasNull()) {
								location.put("name", value);
							}
						}
						
						locationData.put(Long.toString(rs.getLong(1)), location);
					}
					
					return locationData;
				}
			}
		} finally {
			if (System.currentTimeMillis() - ttt > 30000) {
				new Exception().printStackTrace();
			}
		}
	}
	
	@Override
	public JSONObject getManager(long node) throws SQLException {
		long ttt = System.currentTimeMillis();
		try (Connection c = this.connPool.getConnection()) {
			try (PreparedStatement pstmt = c.prepareStatement("SELECT"+
					" M.user, U.name, N.name, M.timestamp"+
					" FROM t_manager AS M"+
					" LEFT JOIN t_node AS N"+
					" ON M.node=N.id"+
					" LEFT JOIN t_user AS U"+
					" ON M.user=U.id"+
					" WHERE M.node=?;")) {
				pstmt.setLong(1, node);
				
				try (ResultSet rs = pstmt.executeQuery()) {
					JSONObject
						managerData = new JSONObject(),
						manager;
					String value;
					
					while (rs.next()) {
						manager = new JSONObject();
					
						manager.put("id", rs.getString(1));
						
						value = rs.getString(2);
						
						if (!rs.wasNull()) {
							manager.put("user", value);	
						}
						
						value = rs.getString(3);
						
						if (!rs.wasNull()) {
							manager.put("node", value);
						}
						
						managerData.put(Long.toString(rs.getLong(4)), manager);
					}
					
					return managerData;
				}
			}
		} finally {
			if (System.currentTimeMillis() - ttt > 30000) {
				new Exception().printStackTrace();
			}
		}
	}
	
	@Override
	public JSONObject getNode(String filter) throws SQLException {
		long ttt = System.currentTimeMillis();
		try (Connection c = this.connPool.getConnection()) {
			try (Statement stmt = c.createStatement()) {
				try (ResultSet rs = stmt.executeQuery(String.format("SELECT"+
					" N.id, N.name, N.type, ip, label, manager, extra, M.protocol, M.status, L.node, COUNT(C.critical)"+
					" FROM t_node AS N"+
					" LEFT JOIN t_monitor AS M"+
					" ON N.id=M.id"+
					" LEFT JOIN t_location AS L"+
					" ON N.id=L.node"+
					" LEFT JOIN t_critical AS C"+
					" ON N.id=C.id"+
					" WHERE %s"+
					" GROUP BY N.id"+
					";", filter == null? "TRUE": String.format("_group='%s'", filter)))) {
					JSONObject
						nodeData = new JSONObject(),
						node;
					long id;
					String value;
					boolean status;
					
					while (rs.next()) {
						id = rs.getLong(1);
						
						node = new JSONObject()
							.put("id", id);
						
						value = rs.getString(2);
						
						if (!rs.wasNull()) {
							node.put("name", value);
						}
						
						value = rs.getString(3);
						
						if (!rs.wasNull()) {
							node.put("type", value);
						}
						
						value = rs.getString(4);
						if (!rs.wasNull()) {
							node.put("ip", value);
						}
						
						value = rs.getString(5);
						
						if (!rs.wasNull()) {
							node.put("label", value);
						}
						
						value = rs.getString(6);
						
						if (!rs.wasNull()) {
							node.put("manager", value);
						}
						
						value = rs.getString(7);
						
						if (!rs.wasNull()) {
							node.put("extra", value);
						}
						
						value = rs.getString(8);
						
						if (!rs.wasNull()) {
							node.put("protocol", value);
						}
						
						status = rs.getBoolean(9);
						
						if (!rs.wasNull()) {
							node.put("status", status);
						}
						
						rs.getLong(10);
						
						if (!rs.wasNull()) {
							node.put("location", true);
						}
						
						if (rs.getInt(11) > 0) {
							node.put("critical", true);
						}
						
						nodeData.put(Long.toString(id), node);
					}
					
					return nodeData;
				}
			}
		} finally {
			if (System.currentTimeMillis() - ttt > 30000) {
				new Exception().printStackTrace();
			}
		}
	}
	
	@Override
	public JSONObject getNode(long id, boolean resource) throws SQLException {
		JSONObject node = new JSONObject();
		boolean status;
		
		long ttt = System.currentTimeMillis();
		try (Connection c = this.connPool.getConnection()) {
			try (PreparedStatement pstmt = c.prepareStatement("SELECT"+
				" N.id, name, type, ip, label, manager, extra, M.protocol, M.status, profile, address, subaddr, phone, lat, lng"+
				" FROM t_node AS N"+
				" LEFT JOIN t_monitor AS M"+
				" ON N.id = M.id"+
				" LEFT JOIN t_branch AS B"+
				" ON N.id = B.id"+
				" WHERE N.id=?"+
				";")) {
				pstmt.setLong(1, id);
				
				try (ResultSet rs = pstmt.executeQuery()) {
					if (rs.next()) {
						String value;
						
						node.put("id", rs.getLong(1));
						
						value = rs.getString(2);
						
						if (!rs.wasNull()) {
							node.put("name", value);
						}
						
						value = rs.getString(3);
						
						if (!rs.wasNull()) {
							node.put("type", value);
						}
						
						value = rs.getString(4);
						
						if (!rs.wasNull()) {
							node.put("ip", value);
						}
						
						value = rs.getString(5);
						
						if (!rs.wasNull()) {
							node.put("label", value);
						}
						
						value = rs.getString(6);
						
						if (!rs.wasNull()) {
							node.put("manager", value);
						}
						
						value = rs.getString(7);
						
						if (!rs.wasNull()) {
							node.put("extra", value);
						}
						
						value = rs.getString(8);
						
						if (!rs.wasNull()) {
							node.put("protocol", value);
						}
						
						status = rs.getBoolean(9);
						
						if (!rs.wasNull()) {
							node.put("status", status);
						}
						
						value = rs.getString(10);
						
						if (!rs.wasNull()) {
							node.put("profile", value);
						}
						
						value = rs.getString(11);
						
						if (!rs.wasNull()) {
							JSONObject branch = new JSONObject();
							
							branch.put("address", value);
							branch.put("subaddr", rs.getString(12));
							branch.put("phone", rs.getString(13));
							
							value = rs.getString(14);
							
							if (!rs.wasNull()) {
								branch.put("lat", value);
							}
							
							value = rs.getString(15);
							
							if (!rs.wasNull()) {
								branch.put("lng", value);
							}
							
							node.put("branch", branch);
						}
			
						if (resource) {
							node.put("resource", resourceManager.get(id));
						}
						
						return node;
					} else {
						return null;
					}
				}
			}
		} finally {
			if (System.currentTimeMillis() - ttt > 30000) {
				new Exception().printStackTrace();
			}
		}
	}
	
	@Override
	public JSONObject getPath() throws SQLException {
		long ttt = System.currentTimeMillis();
		try (Connection c = this.connPool.getConnection()) {
			try (Statement stmt = c.createStatement()) {				
				try (ResultSet rs = stmt.executeQuery("SELECT"+
					" id, node_from, node_to, type, color, size"+
					" FROM t_path;")) {
					JSONObject pathData = new JSONObject();
					long id;
					
					while (rs.next()) {
						id = rs.getLong(1);
						
						pathData.put(Long.toString(id), new JSONObject()
							.put("id", id)
							.put("from", rs.getLong(2))
							.put("to", rs.getLong(3))
							.put("type", rs.getString(4))
							.put("color", rs.getString(5))
							.put("size", rs.getInt(6)));
					}
					
					return pathData;
				}
			}
		} finally {
			if (System.currentTimeMillis() - ttt > 30000) {
				new Exception().printStackTrace();
			}
		}
	}

	@Override
	public JSONObject getPath(long nodeFrom, long nodeTo) throws SQLException {
		long ttt = System.currentTimeMillis();
		try (Connection c = this.connPool.getConnection()) {
			try (PreparedStatement pstmt = c.prepareStatement("SELECT"+
				" id, type, color, size"+
				" FROM t_path"+
				" WHERE node_from=? AND node_to=?;")) {
				pstmt.setLong(1, nodeFrom);
				pstmt.setLong(2, nodeTo);
				
				try (ResultSet rs = pstmt.executeQuery()) {
					if (rs.next()) {
						return new JSONObject()
							.put("id", rs.getLong(1))
							.put("nodeFrom", nodeFrom)
							.put("nodeTo", nodeTo)
							.put("type", rs.getString(2))
							.put("color", rs.getString(3))
							.put("size", rs.getInt(4));
					} else {
						return null;
					}
				}
			}
		} finally {
			if (System.currentTimeMillis() - ttt > 30000) {
				new Exception().printStackTrace();
			}
		}
	}

	@Override
	public JSONObject getPosition(String name) throws SQLException {
		long ttt = System.currentTimeMillis();
		try (Connection c = this.connPool.getConnection()) {
			try (PreparedStatement pstmt = c.prepareStatement("SELECT position"+
				" FROM t_position"+
				" WHERE name=?;")) {
				pstmt.setString(1, name);
				
				try (ResultSet rs = pstmt.executeQuery()) {
					if (rs.next()) {
						return new JSONObject(rs.getString(1));
					} else {
						return null;
					}
				}
			} 
		} finally {
			if (System.currentTimeMillis() - ttt > 30000) {
				new Exception().printStackTrace();
			}
		}
	}

	@Override
	public JSONObject getProfile() throws SQLException {
		long ttt = System.currentTimeMillis();
		try (Connection c = this.connPool.getConnection()) {
			try (Statement stmt = c.createStatement()) {				
				try (ResultSet rs = stmt.executeQuery("SELECT name, protocol, port, version, security, level, auth_protocol, auth_key, priv_protocol, priv_key"+
					" FROM t_profile;")) {
					JSONObject
						profileData = new JSONObject(),
						profile;
					
					while (rs.next()) {
						profile = new JSONObject()
							.put("name", rs.getString(1))
							.put("protocol", rs.getString(2))
							.put("port", rs.getInt(3))
							.put("version", rs.getInt(4))
							.put("security", rs.getString(5))
							.put("level", rs.getInt(6));
						
						if (rs.getString(7) != null) {
							profile.put("authProtocol", rs.getString(6));
						}
						
						if (rs.getString(8) != null) {
							profile.put("authKey", rs.getString(7));
						}
						
						if (rs.getString(9) != null) {
							profile.put("privProtocol", rs.getString(8));
						}
						
						if (rs.getString(10) != null) {
							profile.put("privKey", rs.getString(9));
						}
						
						profileData.put(rs.getString(1), profile);
					}
					
					return profileData;
				}
			}
		} finally {
			if (System.currentTimeMillis() - ttt > 30000) {
				new Exception().printStackTrace();
			}
		}
	}

	@Override
	public JSONObject getProfile(String name) throws SQLException {
		long ttt = System.currentTimeMillis();
		try (Connection c = this.connPool.getConnection()) {
			try (PreparedStatement pstmt = c.prepareStatement("SELECT protocol, port, version, security, level, auth_protocol, auth_key, priv_protocol, priv_key"+ 
				" FROM t_profile"+
				" WHERE name=?;")) {
				pstmt.setString(1, name);
				
				try (ResultSet rs = pstmt.executeQuery()) {
					if (rs.next()) {
						JSONObject profile = new JSONObject()
							.put("name", name)
							.put("protocol", rs.getString(1))
							.put("port", rs.getInt(2))
							.put("version", rs.getInt(3))
							.put("security", rs.getString(4))
							.put("level", rs.getInt(5));
						
						if (rs.getString(6) != null) {
							profile.put("authProtocol", rs.getString(5));
						}
						
						if (rs.getString(7) != null) {
							profile.put("authKey", rs.getString(6));
						}
						
						if (rs.getString(8) != null) {
							profile.put("privProtocol", rs.getString(7));
						}
						
						if (rs.getString(9) != null) {
							profile.put("privKey", rs.getString(8));
						}
						
						return profile;
					} else {
						return null;
					}
				}
			}
		} finally {
			if (System.currentTimeMillis() - ttt > 30000) {
				new Exception().printStackTrace();
			}
		}
	}
	
	@Override
	public JSONObject getRack() throws SQLException {
		long ttt = System.currentTimeMillis();
		try (Connection c = this.connPool.getConnection()) {
			try (Statement stmt = c.createStatement()) {				
				try (ResultSet rs = stmt.executeQuery("SELECT"+
					" id, name, x, y, unit"+ 
					" FROM t_rack;")) {
					JSONObject rackData = new JSONObject();
							
					while (rs.next()) {
						rackData.put(Integer.toString(rs.getInt(1)), new JSONObject()
							.put("id", rs.getInt(1))
							.put("name", rs.getString(2))
							.put("x", rs.getInt(3))
							.put("y", rs.getInt(4))
							.put("unit", rs.getInt(5)));
					}
					
					return rackData;
				}
			}
		} finally {
			if (System.currentTimeMillis() - ttt > 30000) {
				new Exception().printStackTrace();
			}
		}
	}
	
	@Override
	public JSONObject getRack(int id) throws SQLException {
		long ttt = System.currentTimeMillis();
		try (Connection c = this.connPool.getConnection()) {
			try (PreparedStatement pstmt = c.prepareStatement("SELECT"+
				" name, x, y, unit"+ 
				" FROM t_rack"+
				" WHERE id=?;")) {
				pstmt.setInt(1, id);
				
				try (ResultSet rs = pstmt.executeQuery()) {
					if (rs.next()) {
						return new JSONObject()
							.put("id", id)
							.put("name", rs.getString(1))
							.put("x", rs.getInt(2))
							.put("y", rs.getInt(3))
							.put("unit", rs.getInt(4));
					} else {
						return null;
					}
				}
			}
		} finally {
			if (System.currentTimeMillis() - ttt > 30000) {
				new Exception().printStackTrace();
			}
		}
	}
	
	@Override
	public JSONObject getResource(long id, String oid, String index) {
		Value value = resourceManager.getValue(id, oid, index);
		
		if (value == null) {
			return null;
		}
		
		return new JSONObject().put(Long.toString(value.timestamp), value.value);
	}
	
	@Override
	public synchronized JSONObject getResource(long id, String oid, String index, long date) {
		return this.batch.getData(id, oid, index, date);
	}
	
	@Override
	public JSONObject getResource(long id, String oid, String index, long from, long to) {
		return this.batch.getSummary(id, oid, index, from, to);
	}
	
	@Override
	public Path getRoot() {
		return this.root;
	}
	
	@Override
	public JSONObject getSetting() throws SQLException {
		long ttt = System.currentTimeMillis();
		try (Connection c = this.connPool.getConnection()) {
			try (Statement stmt = c.createStatement()) {				
				try (ResultSet rs = stmt.executeQuery("SELECT"+
					" key, value"+
					" FROM t_setting;")) {
					JSONObject settingData = new JSONObject();
					
					while (rs.next()) {
						settingData.put(rs.getString(1), rs.getString(2));
					}
					
					return settingData;
				}
			}
		} finally {
			if (System.currentTimeMillis() - ttt > 30000) {
				new Exception().printStackTrace();
			}
		}
	}

	@Override
	public JSONObject getSetting(String key) throws SQLException {
		long ttt = System.currentTimeMillis();
		try (Connection c = this.connPool.getConnection()) {
			try (PreparedStatement pstmt = c.prepareStatement("SELECT"+
				" value"+
				" FROM t_setting"+
				" WHERE key=?;")) {
				pstmt.setString(1, key);
				
				try (ResultSet rs = pstmt.executeQuery()) {
					if (rs.next()) {
						return new JSONObject().put(key, rs.getString(1));
					} else {
						return null;
					}
				}
			}
		} finally {
			if (System.currentTimeMillis() - ttt > 30000) {
				new Exception().printStackTrace();
			}
		}
	}

	@Override
	public JSONObject getTop(int count) {
		JSONObject top = new JSONObject();
		Parseable parser;
		List<Max> result;
		
		for (Parser p : Parser.values()) {
			parser = p.getInstance();
			
			result = parser.getTop(count, true);
			
			if (result != null) {
				final JSONArray ja = new JSONArray();
				
				top.put(String.format("%s_RATE", parser.toString()), ja);
				
				result.forEach(max ->
					ja.put(new JSONObject()
						.put("id", max.id)
						.put("index", max.index)
						.put("value", max.value)
						.put("rate", max.rate)));
			}
			
			result = parser.getTop(count, false);
			
			if (result != null) {
				final JSONArray ja = new JSONArray();
				
				top.put(parser.toString(), ja);
				
				result.forEach(max ->
					ja.put(new JSONObject()
						.put("id", max.id)
						.put("index", max.index)
						.put("value", max.value)
						.put("rate", max.rate)));
			}
		}
		
		return top;
	}

	@Override
	public JSONObject getTraffic(JSONObject traffic) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public JSONObject getUser(boolean event) throws SQLException {
		long ttt = System.currentTimeMillis();
		try (Connection c = this.connPool.getConnection()) {
			try (Statement stmt = c.createStatement()) {
				String query = String.format("SELECT"+
						" id, name, email, sms, level"+
						" FROM t_user"+
						" WHERE %s;", event? "sms IS NOT NULL OR email IS NOT NULL": "TRUE");				
				try (ResultSet rs = stmt.executeQuery(query)) {
					JSONObject userData = new JSONObject();
					JSONObject user;
					String value;
					
					while (rs.next()) {
						user = new JSONObject();
						
						value = rs.getString(1);
						
						user.put("id", value);
						
						userData.put(value, user);
						
						value = rs.getString(2);
						
						if (!rs.wasNull()) {
							user.put("name", value);
						}
						
						value = rs.getString(3);
						
						if (!rs.wasNull()) {
							user.put("email", value);
						}
						
						value = rs.getString(4);
						
						if (!rs.wasNull()) {
							user.put("sms", value);
						}
						
						user.put("level", rs.getInt(5));
					}
					
					return userData;
				}
			}
		} finally {
			if (System.currentTimeMillis() - ttt > 30000) {
				new Exception().printStackTrace();
			}
		}
	}

	@Override
	public JSONObject getUser(String id) throws SQLException {
		long ttt = System.currentTimeMillis();
		try (Connection c = this.connPool.getConnection()) {
			try (PreparedStatement pstmt = c.prepareStatement("SELECT"+
				" name, email, sms, level"+
				" FROM t_user"+
				" WHERE id=?;")) {
				pstmt.setString(1, id);
				
				try (ResultSet rs = pstmt.executeQuery()) {
					if (rs.next()) {
						JSONObject user = new JSONObject();
						String value;
						
						user.put("id", id);
						user.put("name", rs.getString(1));
						
						value = rs.getString(2);
						
						if (!rs.wasNull()) {
							user.put("email", value);
						}
						
						value = rs.getString(3);
						
						if (!rs.wasNull()) {
							user.put("sms", value);
						}
						
						user.put("level", rs.getInt(4));
						
						return user;
					} else {
						return null;
					}
				}
			}
		} finally {
			if (System.currentTimeMillis() - ttt > 30000) {
				new Exception().printStackTrace();
			}
		}
	}
	
	@Override
	public void informLimitEvent(int limit) {
		
	}
	
	@Override
	public void informPingEvent(long id, long rtt, boolean issue) {
		boolean status = rtt > -1;
		Calendar calendar = Calendar.getInstance();
		
		if (status) {
			String
				index = "0";
			long timestamp = calendar.getTimeInMillis();
			
			resourceManager.getValue(id, "1.3.6.1.4.1.49447.1", index, true).set(timestamp, Long.toString(rtt));
			
			ArrayList<CriticalEvent> list = Parser.RESPONSETIME.getInstance().parse(id);
				
			if (!list.isEmpty()) {
				CriticalEvent ce = list.get(0);
				
				try(Connection c = this.connPool.getConnection()) {
					try (PreparedStatement pstmt = c.prepareStatement("UPDATE"+
						" t_critical"+
						" SET critical=?"+
						" WHERE id=? AND oid=? AND _index=?"+
						";")) {
						pstmt.setLong(2, ce.id);
						pstmt.setString(3, ce.oid);
						pstmt.setString(4, ce.index);
						
						if (ce.critical) {
							pstmt.setBoolean(1, true);	
						} else {
							pstmt.setNull(1, Types.NULL);
						}
						
						pstmt.executeUpdate();
					}
				} catch(SQLException sqle) {
					sqle.printStackTrace();
				}
				
				sendEvent(ce);
			}
		}
		else {
			for (Parser parser : Parser.values()) {
				parser.getInstance().reset(id);
			}
		}
		
		if (issue) {
			try(Connection c = this.connPool.getConnection()) {
				try (PreparedStatement pstmt = c.prepareStatement("UPDATE"+
					" t_monitor"+
					" SET status=?"+
					" WHERE id=?;")) {
					pstmt.setBoolean(1, status);
					pstmt.setLong(2, id);
					
					pstmt.executeUpdate();
				}
				
				sendEvent(new Event(Event.STATUS, id, status? Event.NORMAL: Event.ERROR, String.format("응답 %s", status? "정상": "없음")));
			} catch(SQLException sqle) {
				sqle.printStackTrace();
			}
		}
	}
	
	@Override
	public void informSNMPEvent(long id, int code, boolean issue) {
		if (code == SnmpConstants.SNMP_ERROR_SUCCESS) {
			ArrayList<CriticalEvent> al;
			Parseable parser;
			CriticalEvent ce;
			
			for (Parser p : Parser.values()) {
				parser = p.getInstance();
		
				if (!(parser instanceof ResponseTime)) {
					al = parser.parse(id);
					
					if (parser instanceof HRProcessorLoad) {
						HRProcessorLoad hrplp = (HRProcessorLoad)parser;
						
						Integer load = hrplp.getLoad(id);
						
						if (load != null) {
							ce = hrplp.parse(id, informResourceEvent(id, new OID("1.3.6.1.4.1.49447.4"), new OID("0"), new Integer32(load)));
							
							if (ce != null) {
								al.add(ce);
							}
						}
					}
					
					if (!al.isEmpty()) {
						try(Connection c = this.connPool.getConnection()) {
							try (PreparedStatement pstmt = c.prepareStatement("UPDATE"+
								" t_critical"+
								" SET critical=?"+
								" WHERE id=? AND oid=? AND _index=?"+
								";")) {
								for (CriticalEvent e : al) {
									pstmt.setLong(2, e.id);
									pstmt.setString(3, e.oid);
									pstmt.setString(4, e.index);
									
									if (e.critical) {
										pstmt.setBoolean(1, true);	
									} else {
										pstmt.setNull(1, Types.NULL);
									}
									
									pstmt.executeUpdate();
									
									sendEvent(e);
								}
							}
						}catch(SQLException sqle) {
							sqle.printStackTrace();
						}
					}
				}
				
				parser.submit(id);
			}
		} else {
			for (Parser parser : Parser.values()) {
				if (!parser.equals(Parser.RESPONSETIME)) {
					parser.getInstance().reset(id);
				}
			}
		}
	
		if (issue) {
			try(Connection c = this.connPool.getConnection()) {
				try (PreparedStatement pstmt = c.prepareStatement("UPDATE"+
					" t_monitor"+
					" SET snmp=?"+
					" WHERE id=?;")) {
					pstmt.setInt(1, code);
					pstmt.setLong(2, id);
					
					pstmt.executeUpdate();
				}
				
				sendEvent(new Event(Event.SNMP, id, code == 0? Event.NORMAL: Event.WARNING,
					String.format("SNMP %s", code == 0? "응답 정상": String.format("오류코드 %d", code))));
			} catch(SQLException sqle) {
				sqle.printStackTrace();
			}
		}
	}
	
	@Override
	public Value informResourceEvent(long id, OID requestOID, OID indexOID, Variable variable) {
		Calendar calendar = Calendar.getInstance();
		String
			oid = requestOID.toDottedString(),
			index = indexOID.toDottedString(),
			value;
		Rule rule;
		Value v;
		long timestamp = calendar.getTimeInMillis();
		
		switch (oid) {
			case "1.3.6.1.4.1.9.2.1.5.6":
			case "1.3.6.1.4.1.9.9.109.1.1.1.1.3" :
			case "1.3.6.1.4.1.9.9.109.1.1.1.1.6" :
			case "1.3.6.1.4.1.6296.9.1.1.1.8" :
			case "1.3.6.1.4.1.37288.1.1.3.1.1" :
				oid = "1.3.6.1.2.1.25.3.3.1.2";
				
				break;
		}
		
		rule = ruleMap.get(oid);
		
		switch (rule.syntax) {
			case "DisplayString":
				if (variable instanceof OctetString) {
					value = Util.toValidString(((OctetString)variable).getValue());
				} else {
					value = variable.toString();
				}
				
				break;
			case "TimeTicks":
				if (variable instanceof TimeTicks) {
					value = Long.toString(((TimeTicks)variable).toMilliseconds());
				} else {
					value = variable.toString();
				}
				
				break;
			default:
				value = variable.toString();
		}
		
		v = resourceManager.getValue(id, oid, index);
		
		if (v == null) {
			v = resourceManager.getValue(id, oid, index, true);
		} else if (this.config.ifMon && rule.name.equals("ifOperStatus") && !v.value.equals(value)) {
			Value ifName = resourceManager.getValue(id, "1.3.6.1.2.1.31.1.1.1.1", index);
			
			if (ifName != null) {
				if (Integer.valueOf(value) == 1) {
					sendEvent(new Event(Event.IFMON, id, Event.NORMAL,
						String.format("Interface %s UP.", ifName.value)));
				} else {
					sendEvent(new Event(Event.IFMON, id, Event.ERROR,
						String.format("Interface %s DOWN.", ifName.value)));
				}
			} else {
				if (Integer.valueOf(value) == 1) {
					sendEvent(new Event(Event.IFMON, id, Event.NORMAL,
						String.format("Interface.%s UP.", index)));
				} else {
					sendEvent(new Event(Event.IFMON, id, Event.ERROR,
						String.format("Interface.%s DOWN.", index)));
				}
			}
		}
		
		return v.set(timestamp, value);
	}
	
	public void informTestEvent(long id, String ip, Protocol protocol, Object result) {
		switch (protocol) {
		case ICMP:
			if ((Boolean)result && registerICMPNode(id, ip)) {
				sendEvent(new Event(Event.REGISTER, id, Event.NORMAL, "ICMP 등록 성공."));
			}
			else {
				sendEvent(new Event(Event.REGISTER, id, Event.WARNING, "ICMP 등록 실패."));
			}
			
			break;
		case TCP:
			if ((Boolean)result && registerTCPNode(id, ip)) {
				sendEvent(new Event(Event.REGISTER, id, Event.NORMAL, "TCP 등록 성공."));
			}
			else {
				sendEvent(new Event(Event.REGISTER, id, Event.WARNING, "TCP 등록 실패."));
			}
			
			break;
		default:
			if (result != null && registerSNMPNode(id, ip, (String)result)) {
				sendEvent(new Event(Event.REGISTER, id, Event.NORMAL, "SNMP 등록 성공."));
			}
			else {
				sendEvent(new Event(Event.REGISTER, id, Event.WARNING, "SNMP 등록 실패."));
			}
		}
	}
	
	@Override
	public void onEvent(Object caller, Object ...event) {
		if (caller instanceof SmartSearch) {
			onSearchEvent((String)event[0], (String)event[1]);
		}
	}
	
	private void onSearchEvent(String ip, String profile) {
		long ttt = System.currentTimeMillis();
		try (Connection c = this.connPool.getConnection()) {
			synchronized(this.nextNodeID) {
				long id;
				
				try (PreparedStatement pstmt = c.prepareStatement("SELECT"+
					" id"+
					" FROM t_node"+
					" WHERE ip=?;")) {
					pstmt.setString(1, ip);
					
					try(ResultSet rs = pstmt.executeQuery()) {
						if (rs.next()) {
							id = rs.getLong(1);
						} else {
							id = this.nextNodeID;
							
							try (PreparedStatement pstmt2 = c.prepareStatement("INSERT INTO t_node (id, ip)"+
								" VALUES (?, ?);")) {
								pstmt2.setLong(1, id);
								pstmt2.setString(2, ip);
								
								pstmt2.executeUpdate();
							}
						}
					}
				}
				
				if (registerSNMPNode(id, ip, profile)) {
					sendEvent(new Event(Event.SEARCH, id, Event.NORMAL, "SNMP 노드  탐지."));
				}
				
				this.nextNodeID++;
			}
		} catch (SQLException sqle) {
			sqle.printStackTrace();
		} finally {
			if (System.currentTimeMillis() - ttt > 30000) {
				new Exception().printStackTrace();
			}
		}
	}
	
	private boolean registerSNMPNode(long id, String ip, String profile) {
		long ttt = System.currentTimeMillis();
		try (Connection c = this.connPool.getConnection()) {
			try {
				c.setAutoCommit(false);
				
				try (PreparedStatement pstmt = c.prepareStatement("INSERT INTO"+
					" t_monitor"+
					" (id, protocol, profile)"+
					" VALUES (?, 'snmp', ?);")) {
					pstmt.setLong(1, id);
					pstmt.setString(2, profile);
					
					pstmt.executeUpdate();
				}	
				
				try (PreparedStatement pstmt = c.prepareStatement("SELECT"+
					" port, version, security, level"+
					" FROM t_profile"+
					" WHERE name=?;")) {
					pstmt.setString(1, profile);
					
					try (ResultSet rs = pstmt.executeQuery()) {
						if (rs.next()) {
							this.nodeManager.createNode(id,
								SNMPNode.getInstance(this.nodeManager, id, ip, rs.getInt(1), rs.getString(3), rs.getInt(2), rs.getInt(4)));
						}
					}			
				}
				
				c.commit();
				
				return true;
			} catch (Exception e) {
				c.rollback();
				
				throw e;
			}
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			if (System.currentTimeMillis() - ttt > 30000) {
				new Exception().printStackTrace();
			}
		}
		
		return false;
	}
	
	private boolean registerICMPNode(long id, String ip) {
		long ttt = System.currentTimeMillis();
		try (Connection c = this.connPool.getConnection()) {
			try {
				c.setAutoCommit(false);
				
				try (PreparedStatement pstmt = c.prepareStatement("INSERT INTO"+
					" t_monitor"+
					" (id, protocol)"+
					" VALUES (?, 'icmp');")) {
					pstmt.setLong(1, id);
					
					pstmt.executeUpdate();
					
					this.nodeManager.createNode(id, new ICMPNode(id, ip));
				}
				
				c.commit();
				
				return true;
			} catch (Exception e) {
				c.rollback();
				
				throw e;
			}
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			if (System.currentTimeMillis() - ttt > 30000) {
				new Exception().printStackTrace();
			}
		}
		
		return false;
	}
	
	private boolean registerTCPNode(long id, String ip) {
		long ttt = System.currentTimeMillis();
		try (Connection c = this.connPool.getConnection()) {
			try {
				c.setAutoCommit(false);
			
				try (PreparedStatement pstmt = c.prepareStatement("INSERT INTO"+
					" t_monitor"+
					" (id, protocol)"+
					" VALUES (?, 'tcp');")) {
					pstmt.setLong(1, id);
					
					pstmt.executeUpdate();
				}
				
				this.nodeManager.createNode(id, new TCPNode(id, ip));
				
				c.commit();
				
				return true;
			} catch (Exception e) {
				c.rollback();
				
				throw e;
			}
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			if (System.currentTimeMillis() - ttt > 30000) {
				new Exception().printStackTrace();
			}
		}
		
		return false;
	}
	
	@Override
	public boolean removeBody(long id) {
		long ttt = System.currentTimeMillis();
		try (Connection c = this.connPool.getConnection()) {
			try (PreparedStatement pstmt = c.prepareStatement("DELETE"+
				" FROM t_body"+
				" WHERE id=?;")) {
				pstmt.setLong(1, id);
				
				pstmt.executeUpdate();
			}
			
			return true;
		} catch (SQLException sqle) {
			sqle.printStackTrace();
		} finally {
			if (System.currentTimeMillis() - ttt > 30000) {
				new Exception().printStackTrace();
			}
		}
		
		return false;
	}
	
	@Override
	public boolean removeBranch(long id) {
		long ttt = System.currentTimeMillis();
		try (Connection c = this.connPool.getConnection()) {
			try (PreparedStatement pstmt = c.prepareStatement("DELETE"+
				" FROM t_branch"+
				" WHERE id=?;")) {
				pstmt.setLong(1, id);
				
				pstmt.executeUpdate();
			}
			
			return true;
		} catch (SQLException sqle) {
			sqle.printStackTrace();
		} finally {
			if (System.currentTimeMillis() - ttt > 30000) {
				new Exception().printStackTrace();
			}
		}
		
		return false;
	}
	
	@Override
	public boolean removeFacility(long id) throws SQLException {
		long ttt = System.currentTimeMillis();
		try (Connection c = this.connPool.getConnection()) {
			c.setAutoCommit(false);
			
			try {
				try (Statement stmt = c.createStatement()) {
					try (ResultSet rs = stmt.executeQuery("SELECT"+
						" position"+
						" FROM t_position"+
						" WHERE name='facility';")) {
						if (rs.next()) {
							JSONObject position = new JSONObject(rs.getString(1));
							
							position.remove(Long.toString(id));
							
							try (PreparedStatement pstmt = c.prepareStatement("UPDATE t_position"+
								" SET position=?"+
								" WHERE name='facility';")) {
								pstmt.setString(1, position.toString());
								
								pstmt.executeUpdate();
							}
						}
					}
				}
			
				try (PreparedStatement pstmt = c.prepareStatement("DELETE"+
					" FROM t_facility"+
					" WHERE id=?;")) {
					pstmt.setLong(1, id);
					
					pstmt.executeUpdate();
				}
				
				c.commit();
				
				return true;
			} catch (SQLException sqle) {
				c.rollback();
				
				throw sqle;
			}
		} finally {
			if (System.currentTimeMillis() - ttt > 30000) {
				new Exception().printStackTrace();
			}
		}
	}
	
	@Override
	public boolean removeIcon(String type) {
		long ttt = System.currentTimeMillis();
		try (Connection c = this.connPool.getConnection()) {
			try (PreparedStatement pstmt = c.prepareStatement("DELETE"+
				" FROM t_icon"+
				" WHERE type=?;")) {
				pstmt.setString(1, type);
				
				pstmt.executeUpdate();
				
				return true;
			}
		} catch (SQLException sqle) {
			sqle.printStackTrace();
		} finally {
			if (System.currentTimeMillis() - ttt > 30000) {
				new Exception().printStackTrace();
			}
		}
		
		return false;
	}

	@Override
	public boolean removeLink(long id) {
		long ttt = System.currentTimeMillis();
		try (Connection c = this.connPool.getConnection()) {
			try (PreparedStatement pstmt = c.prepareStatement("DELETE"+
				" FROM t_link"+
				" WHERE id=?;")) {
				pstmt.setLong(1, id);
				
				pstmt.executeUpdate();
			}
			
			return true;
		} catch (SQLException sqle) {
			sqle.printStackTrace();
		} finally {
			if (System.currentTimeMillis() - ttt > 30000) {
				new Exception().printStackTrace();
			}
		}
		
		return false;
	}

	@Override
	public boolean removeLocation(long node) {
		long ttt = System.currentTimeMillis();
		try (Connection c = this.connPool.getConnection()) {
			try (PreparedStatement pstmt = c.prepareStatement("DELETE"+
				" FROM t_location"+
				" WHERE node=?;")) {
				pstmt.setLong(1, node);
				
				pstmt.executeUpdate();
			}
			
			return true;
		} catch (SQLException sqle) {
			sqle.printStackTrace();
		} finally {
			if (System.currentTimeMillis() - ttt > 30000) {
				new Exception().printStackTrace();
			}
		}
		
		return false;
	}
	
	@Override
	public boolean removeNode(long id) {
		long ttt = System.currentTimeMillis();
		try (Connection c = this.connPool.getConnection()) {
			c.setAutoCommit(false);
			
			try {
				try (Statement stmt = c.createStatement()) {
					try (ResultSet rs = stmt.executeQuery("SELECT"+
						" position"+
						" FROM t_position"+
						" WHERE name='position';")) {
						if (rs.next()) {
							JSONObject position = new JSONObject(rs.getString(1));
							
							position.remove(Long.toString(id));
							
							try (PreparedStatement pstmt = c.prepareStatement("UPDATE t_position"+
								" SET position=?"+
								" WHERE name='position';")) {
								pstmt.setString(1, position.toString());
								
								pstmt.executeUpdate();
							}
						}
					}
				}
				
				try (PreparedStatement pstmt = c.prepareStatement("DELETE"+
					" FROM t_node"+
					" WHERE id=?;")) {
					pstmt.setLong(1, id);
					
					pstmt.executeUpdate();
				}
				
				c.commit();
				
				this.nodeManager.removeNode(id);
				
				for (Parser parser : Parser.values()) {
					parser.getInstance().reset(id);
				}
				
				return true;
			} catch (SQLException sqle) {
				c.rollback();
				
				throw sqle;
			}
		} catch (SQLException sqle) {
			sqle.printStackTrace();
		} finally {
			if (System.currentTimeMillis() - ttt > 30000) {
				new Exception().printStackTrace();
			}
		}
		
		return false;
	}

	@Override
	public boolean removePath(long id) {
		long ttt = System.currentTimeMillis();
		try (Connection c = this.connPool.getConnection()) {
			c.setAutoCommit(false);
			
			try {
				try (PreparedStatement pstmt = c.prepareStatement("DELETE"+
					" FROM t_path"+
					" WHERE id=?;")) {
					pstmt.setLong(1, id);
					
					pstmt.executeUpdate();
				}
				
				c.commit();
				
				return true;
			} catch (SQLException sqle) {
				c.rollback();
				
				throw sqle;
			}
		} catch (SQLException sqle) {
			sqle.printStackTrace();
		} finally {
			if (System.currentTimeMillis() - ttt > 30000) {
				new Exception().printStackTrace();
			}
		}
		
		return false;
	}

	@Override
	public boolean removeProfile(String name) {
		long ttt = System.currentTimeMillis();
		try (Connection c = this.connPool.getConnection()) {
			try (PreparedStatement pstmt = c.prepareStatement("DELETE"+
				" FROM t_profile"+
				" WHERE name=?;")) {
				pstmt.setString(1, name);
				
				pstmt.executeUpdate();
			}
	
			return true;	
		} catch (SQLException sqle) {
				sqle.printStackTrace();
		} finally {
			if (System.currentTimeMillis() - ttt > 30000) {
				new Exception().printStackTrace();
			}
		}
		
		return false;
	}

	@Override
	public boolean removeRack(int id) {
		long ttt = System.currentTimeMillis();
		try (Connection c = this.connPool.getConnection()) {
			c.setAutoCommit(false);
			
			try {			
				try (PreparedStatement pstmt = c.prepareStatement("DELETE"+
					" FROM t_rack"+
					" WHERE id=?;")) {
					pstmt.setInt(1, id);
					
					pstmt.executeUpdate();
				}
				
				try (PreparedStatement pstmt = c.prepareStatement("DELETE"+
					" FROM t_location"+
					" WHERE rack=?;")) {
					pstmt.setInt(1, id);
					
					pstmt.executeUpdate();
				}
				
				c.commit();
				
				return true;
			} catch (SQLException sqle) {
				c.rollback();
				
				throw sqle;
			}
		} catch (SQLException sqle) {
			sqle.printStackTrace();
		} finally {
			if (System.currentTimeMillis() - ttt > 30000) {
				new Exception().printStackTrace();
			}
		}
		
		return false;
	}
	
	@Override
	public void removeBandwidth(long id, String index) throws SQLException {
		try (Connection c = this.connPool.getConnection()) {
			try (PreparedStatement pstmt = c.prepareStatement("DELETE"+
				" FROM t_bandwidth"+
				" WHERE id=? AND _index=?;")) {
				pstmt.setLong(1, id);
				pstmt.setString(2, index);
				
				pstmt.executeUpdate();
			}
			
			resourceManager.remove(id, "1.3.6.1.4.1.49447.3.5", index);
		}
	}
	
	@Override
	public void removeUser(String id) throws SQLException {
		long ttt = System.currentTimeMillis();
		try (Connection c = this.connPool.getConnection()) {
			try (PreparedStatement pstmt = c.prepareStatement("DELETE"+
				" FROM t_user"+
				" WHERE id=?;")) {
				pstmt.setString(1, id);
				
				pstmt.executeUpdate();
			}
		} finally {
			if (System.currentTimeMillis() - ttt > 30000) {
				new Exception().printStackTrace();
			}
		}
	}
	
	@Override
	public boolean search(String network, int mask, String name) throws SQLException {
		try {
			JSONObject profileList = getProfile(), profile;
			Profile	args [] = new Profile[profileList.length()];
			int i = 0;
			SmartSearch search;
			
			if (name == null) {
				for (Object o: profileList.keySet()) {
					profile = profileList.getJSONObject((String)o);
					
					args[i++] = profile.has("level")?
						new Profile(
							profile.getString("name"),
							profile.getInt("port"),
							profile.getInt("version"),
							profile.getString("security"),
							profile.getInt("level")):
						new Profile(
							profile.getString("name"),
							profile.getInt("port"),
							profile.getInt("version"),
							profile.getString("security"));
				}
				
				search = new SmartSearch(this.nodeManager, new Network(network, mask), args);
			} else {
				profile = profileList.getJSONObject(name);
				
				search = new SmartSearch(this.nodeManager, new Network(network, mask), profile.has("level")?
					new Profile(
						profile.getString("name"),
						profile.getInt("port"),
						profile.getInt("version"),
						profile.getString("security"),
						profile.getInt("level")):
					new Profile(
						profile.getString("name"),
						profile.getInt("port"),
						profile.getInt("version"),
						profile.getString("security")));
			}
			
			search.addEventListener(this);
			
			search.start();
			
			return true;
		} catch (IOException ioe) {
			ioe.printStackTrace();
		}
		
		return false;
	}
	
	@Override
	public void sendEvent (Event event) {
		try(Connection c = this.connPool.getConnection()) {
			long eventID;
			
			synchronized(this.nextEventID) {
				eventID = this.nextEventID++;
			}
			
			try (PreparedStatement pstmt = c.prepareStatement("INSERT INTO t_event (id, timestamp, origin, level, message, event_id, date)"+
				" VALUES(?, ?, ?, ?, ?, ?, ?);")) {
				Calendar calendar = Calendar.getInstance();
			
				pstmt.setLong(1, event.id);
				pstmt.setLong(2, calendar.getTimeInMillis());
				pstmt.setString(3, "snmp");
				pstmt.setInt(4, event.level);
				pstmt.setString(5, event.message);
				pstmt.setLong(6, eventID);
				
				calendar.set(Calendar.HOUR_OF_DAY, 0);
				calendar.set(Calendar.MINUTE, 0);
				calendar.set(Calendar.SECOND, 0);
				calendar.set(Calendar.MILLISECOND, 0);
				
				pstmt.setLong(7, calendar.getTimeInMillis());
				
				pstmt.executeUpdate();
			}
			
			JSONObject e = event.getJSONObject().put("eventID", eventID);
			
			try (PreparedStatement pstmt = c.prepareStatement("SELECT"+
				" name, ip"+
				" FROM t_node"+
				" WHERE id=?;")) {
				pstmt.setLong(1, event.id);
				try (ResultSet rs = pstmt.executeQuery()) {
					if (rs.next()) {
						String value;
						
						value = rs.getString(1);
						
						if (!rs.wasNull()) {
							e.put("name", value);
						}
						
						value = rs.getString(2);
						
						if (!rs.wasNull()) {
							e.put("ip", value);
						}
					}
				}
			}
			
			nms.onEvent(this, e);
		} catch (SQLException sqle) {
			sqle.printStackTrace();
		}
	}
	
	@Override
	public boolean setBody(long id, JSONObject body) {
		long ttt = System.currentTimeMillis();
		try (Connection c = this.connPool.getConnection()) {
			try (PreparedStatement pstmt = c.prepareStatement("UPDATE t_body"+
				" SET maker=?, name=?, unit=?, front=?, rear=?"+
				" WHERE id=?;")) {
				pstmt.setString(1, body.getString("maker"));
				pstmt.setString(2, body.getString("name"));
				pstmt.setInt(3, body.getInt("unit"));
				pstmt.setString(4, body.getString("front"));
				pstmt.setString(5, body.getString("rear"));
				
				pstmt.executeUpdate();
			}
			
			return true;
		} catch (SQLException sqle) {
			sqle.printStackTrace();
		} finally {
			if (System.currentTimeMillis() - ttt > 30000) {
				new Exception().printStackTrace();
			}
		}
		
		return false;
	}
	
	@Override
	public void setBranch(long id, JSONObject branch) throws SQLException {
		try (Connection c = this.connPool.getConnection()) {
			try (PreparedStatement pstmt = c.prepareStatement("UPDATE"+
				" t_branch"+
				" SET parent=?, name=?, address=?, subaddr=?, phone=?, lat=?, lng=?"+
				" WHERE id=?;")) {
				pstmt.setLong(1, branch.getLong("parent"));
				pstmt.setString(2, branch.getString("name"));
				pstmt.setString(3, branch.getString("address"));
				pstmt.setString(4, branch.getString("subaddr"));
				pstmt.setString(5, branch.getString("phone"));
				
				if (branch.has("lat")) {
					pstmt.setString(6, branch.getString("lat"));	
				} else {
					pstmt.setNull(6, Types.NULL);
				}
				
				if (branch.has("lng")) {
					pstmt.setString(7, branch.getString("lng"));	
				} else {
					pstmt.setNull(7, Types.NULL);
				}
				
				pstmt.setLong(8, id);
				
				pstmt.executeUpdate();
			}
		}
	}
	
	@Override
	public boolean setFacility(long id, JSONObject facility) throws SQLException {
		long ttt = System.currentTimeMillis();
		try (Connection c = this.connPool.getConnection()) {
			try (PreparedStatement pstmt = c.prepareStatement("UPDATE t_facility"+
				" SET"+
				" name=?,"+
				" width=?,"+
				" height=?,"+
				" depth=?,"+
				" rotate=?,"+
				" image=?"+
				" WHERE id=?;")) {
				pstmt.setString(1, facility.getString("name"));
				pstmt.setInt(2, facility.getInt("width"));
				pstmt.setInt(3, facility.getInt("height"));
				pstmt.setInt(4, facility.getInt("depth"));
				pstmt.setInt(5, facility.getInt("rotate"));
				pstmt.setString(6, facility.getString("image"));
				pstmt.setLong(7, id);
				
				pstmt.executeUpdate();
			}
			
			return true;
		}finally {
			if (System.currentTimeMillis() - ttt > 30000) {
				new Exception().printStackTrace();
			}
		}
	}

	@Override
	public void setLimit(long id, String oid, String index, int limit) throws SQLException {
		try (Connection c = this.connPool.getConnection()) {
			if (limit > 0) {
				try (PreparedStatement pstmt = c.prepareStatement("MERGE"+
					" INTO t_critical"+
					" (id, oid, _index, _limit)"+
					" KEY(id, oid, _index)"+
					" VALUES(?, ?, ?, ?);")) {
					pstmt.setLong(1, id);
					pstmt.setString(2, oid);
					pstmt.setString(3, index);
					pstmt.setInt(4, limit);
					
					pstmt.executeUpdate();
				}
			} else {
				try (PreparedStatement pstmt = c.prepareStatement("DELETE"+
					" FROM t_critical"+
					" WHERE id=? AND oid=? AND _index=?;")) {
					pstmt.setLong(1, id);
					pstmt.setString(2, oid);
					pstmt.setString(3, index);
					
					pstmt.executeUpdate();
				}
			}
			
			resourceManager.getValue(id, oid, index, true).limit = limit;
		}
	}

	@Override
	public void setIcon(String type, JSONObject icon) throws SQLException {
		try (Connection c = this.connPool.getConnection()) {
			try (PreparedStatement pstmt = c.prepareStatement("UPDATE t_icon SET"+
				" _group=?,"+
				" src=?,"+
				" disabled=?,"+
				" shutdown=?"+
				" WHERE type=?;")) {
				pstmt.setString(1, icon.getString("group"));
				pstmt.setString(2, icon.getString("src"));
				pstmt.setString(3, icon.getString("disabled"));
				pstmt.setString(4, icon.getString("shutdown"));
				pstmt.setString(5, type);
				
				pstmt.executeUpdate();
			}
		}
	}

	@Override
	public void setIFMon(boolean enable) {
		this.config.ifMon = enable;
	}
	
	@Override
	public void setLink(JSONObject link) throws SQLException {
		try (Connection c = this.connPool.getConnection()) {
			try (PreparedStatement pstmt = c.prepareStatement("UPDATE t_link SET"+
					" index_from=?"+
					", index_from_name=?"+
					", index_to=?"+
					", index_to_name=?"+
					", extra=?"+
					" WHERE id=?;")) {
		
				if (link.has("indexFrom")) {
					pstmt.setLong(1, link.getLong("indexFrom"));
				} else {
					pstmt.setNull(1, Types.NULL);
				}
				
				if (link.has("indexFromName")) {
					pstmt.setString(2, link.getString("indexFromName"));
				} else {
					pstmt.setNull(2, Types.NULL);
				}
				
				if (link.has("indexTo")) {
					pstmt.setLong(3, link.getLong("indexTo"));
				} else {
					pstmt.setNull(3, Types.NULL);
				}
				
				if (link.has("indexToName")) {
					pstmt.setString(4, link.getString("indexToName"));
				} else {
					pstmt.setNull(4, Types.NULL);
				}
				
				if (link.has("extra")) {
					pstmt.setString(5,  link.getJSONObject("extra").toString());
				} else {
					pstmt.setNull(5, Types.NULL);
				}
				
				pstmt.setLong(6, link.getLong("id"));
				
				pstmt.executeUpdate();
			}
		}
	}

	@Override
	public boolean setLocation(long node, JSONObject location) throws SQLException {
		long ttt = System.currentTimeMillis();
		try (Connection c = this.connPool.getConnection()) {
			try (PreparedStatement pstmt = c.prepareStatement("MERGE INTO t_location"+
				" (node, maker, model, rack, position)"+
				" KEY(node)"+
				" VALUES(?, ?, ?, ?, ?);")) {
				pstmt.setLong(1, node);
				pstmt.setString(2, location.getString("maker"));
				pstmt.setString(3, location.getString("model"));
				pstmt.setInt(4, location.getInt("rack"));
				pstmt.setInt(5, location.getInt("position"));
				
				pstmt.executeUpdate();
			}
			
			return true;
		} finally {
			if (System.currentTimeMillis() - ttt > 30000) {
				new Exception().printStackTrace();
			}
		}
	}
	
	@Override
	public void setManager(long node, String user) throws SQLException {
		try (Connection c = this.connPool.getConnection()) {
			try {
				c.setAutoCommit(false);
			
				try (PreparedStatement pstmt = c.prepareStatement("UPDATE"+
					" t_node"+
					" SET manager=?"+
					" WHERE id=?"+
					";")) {
					pstmt.setString(1, user);
					pstmt.setLong(2, node);
					
					pstmt.executeUpdate();
				}
				
				try (PreparedStatement pstmt = c.prepareStatement("INSERT INTO"+
					" t_manager"+
					" (node, user, timestamp)"+
					" VALUES(?, ?, ?)"+
					";")) {
					pstmt.setLong(1, node);
					pstmt.setString(2, user);
					pstmt.setLong(3, System.currentTimeMillis());
					
					pstmt.executeUpdate();
				}
				
				c.commit();
			} catch (SQLException sqle) {
				c.rollback();
				
				throw sqle;
			}
		}
	}
	
	@Override
	public void setMonitor(long id, String ip, String protocol) throws SQLException {
		if (protocol == null) {
			try (Connection c = this.connPool.getConnection()) {
				try (PreparedStatement pstmt = c.prepareStatement("DELETE"+
					" FROM t_monitor"+
					" WHERE id=?;")) {
					pstmt.setLong(1, id);
					
					pstmt.executeUpdate();
					
					this.nodeManager.removeNode(id);
				}
			}
		} else {
			JSONObject
				profileData = getProfile(),
				profile;
			
			switch (protocol.toUpperCase()) {
			case "SNMP":
				Profile args [] = new Profile [profileData.length()];
				int i = 0;
				
				for (Object key : profileData.keySet()) {
					profile = profileData.getJSONObject((String)key);
					
					args[i++] = new Profile(profile.getString("name"),
						profile.getInt("port"),
						profile.getInt("version"),
						profile.getString("security"),
						profile.has("level")? profile.getInt("level"): 0);
				}
				
				this.nodeManager.testNode(id, ip, protocol, args);
			
				break;
			case "ICMP":
			case "TCP":
				this.nodeManager.testNode(id, ip, protocol);
				
				break;
			default:
				profile = profileData.getJSONObject(protocol);
				
				this.nodeManager.testNode(id, ip, protocol,
					new Profile(profile.getString("name"),
						profile.getInt("port"),
						profile.getInt("version"),
						profile.getString("security"),
						profile.has("level")? profile.getInt("level"): 0));
			}
		}
	}

	@Override
	public void setNode(long id, JSONObject node) throws SQLException {
		try (Connection c = this.connPool.getConnection()) {
			c.setAutoCommit(false);
			
			try {
				try (PreparedStatement pstmt = c.prepareStatement("UPDATE t_node"+
					" SET name=?, type=?, label=?, extra=?"+
					" WHERE id=?;")) {
					if (node.has("name")) {
						pstmt.setString(1, node.getString("name"));
					}
					else {
						pstmt.setNull(1, Types.NULL);
					}
					
					if (node.has("type")) {
						pstmt.setString(2, node.getString("type"));
					}
					else {
						pstmt.setNull(2, Types.NULL);
					}
					
					if (node.has("label")) {
						pstmt.setString(3, node.getString("label"));
					}
					else {
						pstmt.setNull(3, Types.NULL);
					}
					
					if (node.has("extra")) {
						pstmt.setString(4, node.getString("extra"));
					}
					else {
						pstmt.setNull(4, Types.NULL);
					}
					
					pstmt.setLong(5, id);
					
					pstmt.executeUpdate();
				}
				
				if (node.has("branch")) {
					JSONObject branch = node.getJSONObject("branch");
					
					try (PreparedStatement pstmt = c.prepareStatement("MERGE INTO"+
						" t_branch"+
						" (id, address, subaddr, phone, lat, lng)"+
						" KEY(id)"+
						" VALUES(?, ?, ?, ?, ?, ?);")) {
						pstmt.setLong(1, id);
						pstmt.setString(2, branch.getString("address"));
						pstmt.setString(3, branch.getString("subaddr"));
						pstmt.setString(4, branch.getString("phone"));
						
						if (branch.has("lat")) {
							pstmt.setString(5, branch.getString("lat"));
						} else {
							pstmt.setNull(5, Types.NULL);
						}
						
						if (branch.has("lng")) {
							pstmt.setString(6, branch.getString("lng"));
						} else {
							pstmt.setNull(6, Types.NULL);
						}
						
						pstmt.executeUpdate();
					}
				} else {
					try (PreparedStatement pstmt = c.prepareStatement("DELETE"+
						" FROM t_branch"+
						" WHERE id=?;")) {
						pstmt.setLong(1, id);
						
						pstmt.executeUpdate();
					}
				}
				
				c.commit();
			} catch (SQLException sqle) {
				c.rollback();
				
				throw sqle;
			}
		}
	}
	
	@Override
	public void setPassword(String id, String password) throws SQLException {
		try (Connection c = this.connPool.getConnection()) {
			try (PreparedStatement pstmt = c.prepareStatement("UPDATE t_user"+
				" SET password=?"+
				" WHERE id=?;")) {
				pstmt.setString(1, password);
				pstmt.setString(2, id);
					
				pstmt.executeUpdate();
			}
		}
	}
	
	@Override
	public void setPath(JSONObject path) throws SQLException {
		try (Connection c = this.connPool.getConnection()) {
			try (PreparedStatement pstmt = c.prepareStatement("UPDATE t_path"+
				" SET type=?, color=?, size=?"+
				" WHERE id=?;")) {
				
				pstmt.setString(1, path.getString("type"));	
				pstmt.setString(2, path.getString("color"));
				pstmt.setLong(3, path.getInt("size"));
				pstmt.setLong(4, path.getLong("id"));
				
				pstmt.executeUpdate();
			}
		}
	}

	@Override
	public void setPosition(String name, JSONObject position) throws SQLException {
		long ttt = System.currentTimeMillis();
		try (Connection c = this.connPool.getConnection()) {
			try (PreparedStatement pstmt = c.prepareStatement("MERGE INTO t_position"+
				" (name, position)"+
				" KEY(name)"+
				" VALUES(?, ?);")) {
				pstmt.setString(1, name);
				pstmt.setString(2, position.toString());
				
				pstmt.executeUpdate();
			}
		} finally {
			if (System.currentTimeMillis() - ttt > 30000) {
				new Exception().printStackTrace();
			}
		}
	}
	
	@Override
	public void setRack(int id, JSONObject rack) throws SQLException {
		try (Connection c = this.connPool.getConnection()) {
			try (PreparedStatement pstmt = c.prepareStatement("UPDATE t_facility"+
				" SET unit=?"+
				" WHERE id=?;")) {
				pstmt.setInt(1, rack.getInt("unit"));
				pstmt.setInt(2, id);
				
				pstmt.executeUpdate();
			}
		}
	}

	@Override
	public void setRack(JSONObject rackData) throws SQLException {
		try (Connection c = this.connPool.getConnection()) {
			try (PreparedStatement pstmt = c.prepareStatement("MERGE INTO t_rack"+
				" (id, name, x, y, unit)"+
				" KEY(id)"+
				" VALUES(?, ?, ?, ?, ?);")) {
				JSONObject rack;
				for (Object id: rackData.keySet()) {
					rack = rackData.getJSONObject((String)id);
				
					pstmt.setInt(1, rack.getInt("id"));
					pstmt.setString(2, rack.getString("name"));
					pstmt.setInt(3, rack.getInt("x"));
					pstmt.setInt(4, rack.getInt("y"));
					pstmt.setInt(5, rack.getInt("unit"));
					
					pstmt.executeUpdate();
				}
			}
		}
	}
	
	@Override
	public void setRetry(int retry) throws SQLException {
		try (Connection c = this.connPool.getConnection()) {
			try (PreparedStatement pstmt = c.prepareStatement("MERGE INTO t_config"+
				" (key, value)"+
				" KEY(key)"+
				" VALUES('retry', ?);")) {
				pstmt.setString(1, Integer.toString(retry));
				
				pstmt.executeUpdate();
			}
			
			this.config.retry = retry;
			
			this.nodeManager.setRetry(retry);
		}
	}

	@Override
	public void setRequestInterval(long interval)
		throws SQLException {
		try (Connection c = this.connPool.getConnection()) {
			try (PreparedStatement pstmt = c.prepareStatement("MERGE INTO t_config"+
				" (key, value)"+
				" KEY(key)"+
				" VALUES('requestInterval', ?);")) {
				pstmt.setString(1, Long.toString(interval));
				
				pstmt.executeUpdate();
			}
			
			this.config.requestInterval = interval;
			
			this.nodeManager.setInterval(interval);
			
		}
	}

	@Override
	public void setSaveInterval(int interval) throws SQLException {
		try (Connection c = this.connPool.getConnection()) {
			try (PreparedStatement pstmt = c.prepareStatement("MERGE INTO t_config"+
				" (key, value)"+
				" KEY(key)"+
				" VALUES('saveInterval', ?);")) {
				pstmt.setString(1, Integer.toString(interval));
				
				pstmt.executeUpdate();
			}
			
			this.config.saveInterval = interval;
			
			this.batch.schedule(interval);
		}
	}

	@Override
	public void setSetting(String key, String value) throws SQLException {
		try (Connection c = this.connPool.getConnection()) {
			try (PreparedStatement pstmt = c.prepareStatement("MERGE INTO t_setting"+
				" (key, value)"+
				" KEY(key)"+
				" VALUES(?, ?);")) {
		
				pstmt.setString(1, key);
				
				if (value == null) {
					pstmt.setNull(2, Types.NULL);
				}
				else {
					pstmt.setString(2, value);
				}
				
				pstmt.executeUpdate();
			}
		}
	}

	@Override
	public boolean setSMTP(JSONObject smtp) {
		long ttt = System.currentTimeMillis();
		try (Connection c = this.connPool.getConnection()) {
			if (smtp == null) {
				try (Statement stmt = c.createStatement()){
					stmt.executeUpdate("UPDATE t_config"+
					" SET value='false'"+
					" WHERE key='smtpEnable';");						
				}
				
				this.config.smtpEnable = false;
			} else {
				String
					server = smtp.getString("server"),
					protocol = smtp.getString("protocol"),
					user = smtp.getString("user"),
					pass = smtp.getString("password");
				
				try (PreparedStatement pstmt = c.prepareStatement("MERGE INTO t_config"+
					" (key, value)"+
					" KEY(key)"+
					" VALUES('smtpServer', ?)"+
					" ,('smtpProtocol', ?)"+
					" ,('smtpUser', ?)"+
					" ,('smtpPassword', ?)"+
					" ,('smtpEnable', 'true');")) {
					
					pstmt.setString(1, server);
					pstmt.setString(2, protocol);
					pstmt.setString(3, user);
					pstmt.setString(4, pass);
					
					pstmt.executeUpdate();
				}
				
				this.config.smtpEnable = true;
				this.config.smtpServer = server;
				this.config.smtpProtocol = protocol;
				this.config.smtpUser = user;
				this.config.smtpPassword = pass;
			}
			
			return true;
		} catch (SQLException sqle) {					
			sqle.printStackTrace();
		} finally {
			if (System.currentTimeMillis() - ttt > 30000) {
				new Exception().printStackTrace();
			}
		}
		
		return false;
	}

	@Override
	public void setAuth(String id, JSONObject auth) throws SQLException {
		int level = auth.getInt("level");
		String query = "";
		
		if (auth.has("password")) {
			query = String.format(", password='%s'", auth.getString("password"));
		}
		
		long ttt = System.currentTimeMillis();
		try (Connection c = this.connPool.getConnection()) {
			try (PreparedStatement pstmt = c.prepareStatement("UPDATE t_user"+
				" SET level=?"+
				query +
				" WHERE id=?;")) {
				pstmt.setInt(1, level);
				pstmt.setString(2, id);
				
				pstmt.executeUpdate();
			}
			
		} finally {
			if (System.currentTimeMillis() - ttt > 30000) {
				new Exception().printStackTrace();
			}
		}
	}
	
	@Override
	public void setBandwidth(long id, String index, String value) throws SQLException {
		try (Connection c = this.connPool.getConnection()) {
			try (PreparedStatement pstmt = c.prepareStatement("MERGE INTO t_bandwidth"+
				" (id, _index, value)"+
				" KEY(id, _index)"+
				" VALUES(?, ?, ?);")) {
				pstmt.setLong(1, id);
				pstmt.setString(2, index);
				pstmt.setString(3, value);
				
				pstmt.executeUpdate();
			}
			
			resourceManager.getValue(id, "1.3.6.1.4.1.49447.3.5", index, true).value = value;
		}
	}

	@Override
	public void setStoreDate(int period) throws SQLException {
		try (Connection c = this.connPool.getConnection()) {
			try (PreparedStatement pstmt = c.prepareStatement("MERGE INTO t_config"+
				" (key, value)"+
				" KEY(key)"+
				" VALUES('storeDate', ?)")) {
				pstmt.setString(1, Integer.toString(period));
				
				pstmt.executeUpdate();
			}
			
			this.config.storeDate = period;
			
			this.batch.setStoreDate(period);
		}
	}

	@Override
	public boolean setSyslog(String address) {
		long ttt = System.currentTimeMillis();
		try (Connection c = this.connPool.getConnection()) {
			if (address == null) {
				try (Statement stmt = c.createStatement()) {
					stmt.executeUpdate("DELETE"+
						" FROM t_config"+
						" WHERE key='syslog'"+
						";");
				}
			} else {
				try (PreparedStatement pstmt = c.prepareStatement("MERGE INTO t_config"+
					" (key, value)"+
					" KEY(key)"+
					" VALUES('syslog', ?)")) {
					pstmt.setString(1, address);
					
					pstmt.executeUpdate();
				}
			}
			
			this.config.syslog = address;
			
			return true;
		} catch (SQLException sqle) {
			sqle.printStackTrace();
		} finally {
			if (System.currentTimeMillis() - ttt > 30000) {
				new Exception().printStackTrace();
			}
		}
		
		return false;
	}
	
	@Override
	public void setTimeout(int timeout) throws SQLException {
		try (Connection c = this.connPool.getConnection()) {
			try (PreparedStatement pstmt = c.prepareStatement("MERGE INTO t_config"+
				" (key, value)"+
				" KEY(key)"+
				" VALUES('timeout', ?);")) {
				pstmt.setString(1, Integer.toString(timeout));
				
				pstmt.executeUpdate();
			}
			
			this.config.timeout = timeout;
			
			this.nodeManager.setTimeout(timeout);
		}
	}

	@Override
	public void setUser(String id, JSONObject user) throws SQLException {
		try (Connection c = this.connPool.getConnection()) {
			try (PreparedStatement pstmt = c.prepareStatement("UPDATE t_user"+
				" SET name=?, email=?, sms=?"+
				" WHERE id=?;")) {
				
				pstmt.setString(1, user.getString("name"));	
				
				if (user.has("email")) {
					pstmt.setString(2, user.getString("email"));	
				} else {
					pstmt.setNull(2, Types.NULL);
				}
				
				if (user.has("sms")) {
					pstmt.setString(3, user.getString("sms"));	
				} else {
					pstmt.setNull(3, Types.NULL);
				}
				
				pstmt.setString(4, id);
				
				pstmt.executeUpdate();
			}
		}
	}
	
	@Override
	public JSONObject signIn(String id, String password) throws SQLException {
		try (Connection c = connPool.getConnection()) {
			try (PreparedStatement pstmt = c.prepareStatement("SELECT level FROM t_user"+
				" WHERE level<2 AND id=? AND password=?;")) {
				pstmt.setString(1, id);
				pstmt.setString(2, password);
				
				try (ResultSet rs = pstmt.executeQuery()) {
					if (rs.next()) {
						return new JSONObject()
							.put("id", id)
							.put("level", rs.getInt(1));
					}
					
					return null;
				}
			}
		}
	}
	
	@Override
	public void start() {
		long ttt = System.currentTimeMillis();
		try (Connection c = this.connPool.getConnection()) {
			try (Statement stmt = c.createStatement()) {
				try (ResultSet rs = stmt.executeQuery("SELECT"+
					" security, level, auth_protocol, auth_key, priv_protocol, priv_key"+
					" FROM t_profile"+
					" WHERE version=3;")) {
					while (rs.next()) {
						this.nodeManager.addUSMUser(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6));	
					}
				}
			}
			
			try (Statement stmt = c.createStatement()) {
				try (ResultSet rs = stmt.executeQuery("SELECT"+
					" M.id, N.ip, M.protocol, port, version, security, level, status, snmp"+
					" FROM t_monitor AS M"+
					" LEFT JOIN t_node AS N"+
					" ON M.id = N.id"+
					" LEFT JOIN t_profile AS P"+
					" ON M.profile = P.name"+
					";")) {
					long
						id,
						count = 0;
					
					while (rs.next()) {
						System.out.print("!");
					
						if (++count %20 == 0) {
							System.out.println();
						}
						
						id = rs.getLong(1);
						
						try {
							switch(rs.getString(3).toUpperCase()) {
							case "ICMP":
								this.nodeManager.createNode(id,
									new ICMPNode(id, rs.getString(2)),
									rs.getBoolean(8));
								
								break;
							case "TCP":
								this.nodeManager.createNode(id,
									new TCPNode(id, rs.getString(2)),
									rs.getBoolean(8));
								
								break;
							case "SNMP":
								this.nodeManager.createNode(id,
									SNMPNode.getInstance(this.nodeManager, id, rs.getString(2), rs.getInt(4), rs.getString(6), rs.getInt(5), rs.getInt(7)),
									rs.getBoolean(8));
								
								break;
							}
						} catch (IOException ioe) {
							ioe.printStackTrace();
						}
					}
					
					System.out.format("\n%d Nodes initialized.\n", count);
				}
			}
		} catch (SQLException sqle) {
			sqle.printStackTrace();
		} finally {
			if (System.currentTimeMillis() - ttt > 30000) {
				new Exception().printStackTrace();
			}
		}
	}
	
	@Override
	public void test(Object... args) {
		JSONObject jsono = (JSONObject)args[0];
		JSONObject locationData = jsono.getJSONObject("location");
		
		try (Connection c = this.connPool.getConnection()) {
			/*try (PreparedStatement pstmt = c.prepareStatement("insert into t_node (id, name, ip) values (?, ?, ?);")) {
				JSONObject node;
				
				for (int i=1, _i=jsona.length(); i<_i; i++) {
					node = jsona.getJSONObject(i);
					
					pstmt.setLong(1, i);
					pstmt.setString(2, node.getString("name"));
					
					if (node.has("ip")) {
						pstmt.setString(3, node.getString("ip"));
					} else {
						pstmt.setNull(3, Types.NULL);
					}
					
					pstmt.executeUpdate();
				}
			}*/
			/*
			try (PreparedStatement pstmt = c.prepareStatement("insert into t_facility"+
					" (id, name, type, unit, width, height, depth)"+
					" values (?, ?, 'rack', 42, 600, 2100, 900);")) {
				for (int i=4; i<13; i++) {
					pstmt.setLong(1, i);
					pstmt.setString(2, String.format("Rack%d", i));
					
					pstmt.executeUpdate();
				}
			}
			*/
			try (PreparedStatement pstmt = c.prepareStatement("delete from t_location;")) {
				pstmt.executeUpdate();
			}
			
			try (PreparedStatement pstmt = c.prepareStatement("insert into t_location"+
				" (node, maker, model, rack, position)"+
				" values (?, ?, ?, ?, ?);")) {
				JSONObject location;
				
				for (Object key : locationData.keySet()) {
					location = locationData.getJSONObject((String)key);
					
					pstmt.setLong(1, Long.valueOf((String)key));
					pstmt.setString(2, location.getString("maker"));
					pstmt.setString(3, location.getString("model"));
					pstmt.setInt(4, location.getInt("rack"));
					pstmt.setInt(5, location.getInt("position"));
					
					pstmt.executeUpdate();
				}
			}
		} catch (SQLException sqle) {
			sqle.printStackTrace();
		}
	}
}
