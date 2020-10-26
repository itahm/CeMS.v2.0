package com.itahm.nms;

import java.io.Closeable;

import com.itahm.json.JSONObject;
import com.itahm.nms.Bean.Event;

public interface Commander extends Closeable {
	public boolean addBody(JSONObject body);
	public boolean addBranch(JSONObject branch);
	public JSONObject addIcon(String type, JSONObject icon);
	public boolean addLink(long path);
	public JSONObject addNode(JSONObject node);
	public boolean addPath(long nodeFrom, long nodeTo);
	public boolean addProfile(String name, JSONObject profile);
	public boolean addRack(JSONObject rack);
	public boolean addUser(String name, JSONObject user);
	public void backup() throws Exception;
	public JSONObject getBranch();
	public JSONObject getBranch(long id);
	public JSONObject getBody();
	public JSONObject getBody(long id);
	public JSONObject getConfig();
	public JSONObject getEvent(long eventID);
	public JSONObject getEvent(JSONObject search);
	public JSONObject getEventByDate(long date);
	public JSONObject getIcon();
	public JSONObject getIcon(String type);
	public JSONObject getInformation();
	public JSONObject getLimit();
	public JSONObject getLimit(long id, String index, String oid);
	public JSONObject getLink();
	public JSONObject getLink(long path);
	public JSONObject getLocation();
	public JSONObject getLocation(long node);
	public JSONObject getNode(String filter);
	public JSONObject getNode(long id, boolean snmp);
	public JSONObject getPath();
	public JSONObject getPath(long nodeFrom, long nodeTo);
	public JSONObject getPosition(String name);
	public JSONObject getProfile();
	public JSONObject getProfile(String name);
	public JSONObject getResource(long id, String oid, String index, long date);
	public JSONObject getResource(long id, String oid, String index);
	public JSONObject getResource(long id, String oid, String index, long from, long to);
	public JSONObject getRack();
	public JSONObject getRack(int id);
	public JSONObject getSetting();
	public JSONObject getSetting(String key);
	public JSONObject getTop(int count);
	public JSONObject getTraffic(JSONObject traffic);
	public JSONObject getUser();
	public JSONObject getUser(String name);
	public void sendEvent (Event event);
	public boolean setBandwidth(long id, String index, String value);
	public boolean setBranch(long id, JSONObject branch);
	public boolean setBody(long id, JSONObject body);
	public boolean setLimit(long id, String oid, String index, int limit);
	public boolean setIcon(String id, JSONObject icon);
	public void setIFMon(boolean enable);
	public boolean setLink(JSONObject link);
	public boolean setLocation(long node, JSONObject location);
	public boolean setMonitor(long id, String ip, String protocol);
	public boolean setNode(long id, JSONObject node);
	public boolean setPath(JSONObject path);
	public boolean setPosition(String name, JSONObject position);
	public boolean setRetry(int retry);
	public boolean setRack(JSONObject rack);
	public boolean setRack(int id, JSONObject rack);
	public boolean setRequestInterval(long interval);
	public boolean setSaveInterval(int interval);
	public boolean setSetting(String key, String value);
	public boolean setSMTP(JSONObject smtp);
	public boolean setStoreDate(int period);
	public boolean setSyslog(String address);
	public boolean setTimeout(int timeout);
	public boolean setUser(String id, JSONObject user);
	public void start();
	public boolean removeBandwidth(long id, String index);
	public boolean removeBranch(long id);
	public boolean removeBody(long id);
	public boolean removeIcon(String type);
	public boolean removeLink(long id);
	public boolean removeLocation(long node);
	public boolean removeNode(long id);
	public boolean removePath(long id);
	public boolean removeProfile(String name);
	public boolean removeRack(int id);
	public boolean removeUser(String name);
	public boolean search(String network, int mask, String profile);
}