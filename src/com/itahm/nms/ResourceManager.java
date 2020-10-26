package com.itahm.nms;

import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.itahm.json.JSONObject;
import com.itahm.nms.Bean.Value;
import com.itahm.nms.Bean.Rollable;
import com.itahm.nms.Bean.Counter;

public class ResourceManager {

	private final Set<Rollable> rollables = ConcurrentHashMap.newKeySet();
	private final Map<Long, Map<String, Map<String, Value>>> oidIndex = new ConcurrentHashMap<>();
	private final Map<Long, Map<String, Map<String, Value>>> idxIndex = new ConcurrentHashMap<>();
	
	public Value getValue(long id, String oid, String index, boolean create) {
		if (!create) {
			return getValue(id, oid, index);
		}
		
		Map<String, Map<String, Value>> oidMap = this.oidIndex.get(id);
		Map<String, Value> indexMap;
		Value value = null;
		
		if (oidMap == null) {
			oidMap = new ConcurrentHashMap<>();
			
			this.oidIndex.put(id, oidMap);
			
			indexMap = new ConcurrentHashMap<>();
			
			oidMap.put(oid, indexMap);
		} else {
			indexMap = oidMap.get(oid);
			
			if (indexMap == null) {
				indexMap = new ConcurrentHashMap<>();
				
				oidMap.put(oid, indexMap);
			} else {
				value = indexMap.get(index);
			}
		}
		
		if (value == null) {
			value = createValue(id, oid, index);
			
			indexMap.put(index, value);
		}
		
		return value;
	}
	
	public Value getValue(long id, String oid, String index) {
		Map<String, Map<String, Value>> oidMap = this.oidIndex.get(id);
		
		if (oidMap == null) {
			return null;
		}
		
		Map<String, Value> indexMap = oidMap.get(oid);
		
		if (indexMap == null) {
			return null;
		}
		
		return indexMap.get(index);
	}
	
	public void remove(long id, String oid, String index) {
		Map<String, Map<String, Value>> map;
		Map<String, Value> sub;
		Value value = null;
		
		map = this.oidIndex.get(id);
		
		if (map != null) {
			sub = map.get(oid);
			
			if (sub != null) {
				value = sub.remove(index);
				
				if (sub.size() == 0) {
					map.remove(oid);
					
					if (map.size() == 0) {
						this.oidIndex.remove(id);
					}
				}
			}
		}
		
		map = this.idxIndex.get(id);
		
		if (map != null) {
			sub = map.get(index);
			
			if (sub != null) {
				value = sub.remove(oid);
				
				if (sub.size() == 0) {
					map.remove(index);
					
					if (map.size() == 0) {
						this.idxIndex.remove(id);
					}
				}
			}
		}
		
		if (value != null) {
			this.rollables.remove(value);
		}
	}
	
	private Value createValue(long id, String oid, String index) {
		Value value;
		
		switch (oid) {
		case "1.3.6.1.2.1.2.2.1.10":
		case "1.3.6.1.2.1.2.2.1.14":
		case "1.3.6.1.2.1.2.2.1.16":
		case "1.3.6.1.2.1.2.2.1.20":
		case "1.3.6.1.2.1.31.1.1.1.6":
		case "1.3.6.1.2.1.31.1.1.1.10":
			value = new Counter(id, oid, index);
			
			this.rollables.add((Counter)value);
			
			break;
		case "1.3.6.1.2.1.25.2.3.1.6":
		case "1.3.6.1.4.1.49447.1":
		case "1.3.6.1.4.1.49447.4":
			value = new Rollable(id, oid, index);
			
			this.rollables.add((Rollable)value);
			
			break;
		default:
			value = new Value();
		}
		
		createIndex(id, oid, index, value);
		
		return value;
	}
	
	private void createIndex(long id, String oid, String index, Value value) {
		Map<String, Map<String, Value>> indexMap = this.idxIndex.get(id);
		Map<String, Value> oidMap;
		
		if (indexMap == null) {
			indexMap = new ConcurrentHashMap<>();
			
			this.idxIndex.put(id, indexMap);
			
			oidMap = new ConcurrentHashMap<>();
			
			indexMap.put(index, oidMap);
			
			oidMap.put(oid, value);
		} else {
			oidMap = indexMap.get(index);
			
			if (oidMap == null) {
				oidMap = new ConcurrentHashMap<>();
				
				indexMap.put(index, oidMap);
			}
			
			oidMap.put(oid, value);
		}
	}
	
	public void forEachIndex(long id, String oid, ForEach f) {
		Map<String, Map<String, Value>> oidMap = this.oidIndex.get(id);
		
		if (oidMap == null) {
			return;
		}
		
		Map<String, Value> indexMap = oidMap.get(oid);
		
		if (indexMap == null) {
			return;
		}
		
		for (String index : indexMap.keySet()) {
			f.forEach(index, indexMap.get(index));
		}
	}
	
	public void forEachRollables(ForEachRollable f) {
		for (Rollable rollable : this.rollables) {
			f.forEach(rollable);
		}
	}
	
	public ArrayList<Value> getByIndex(long id, String index, String... oids) {
		Map<String, Map<String, Value>> indexMap = this.idxIndex.get(id);
		ArrayList<Value> list;
		
		if (indexMap == null) {
			return null;
		}
		
		Map<String, Value> oidMap = indexMap.get(index);
		
		if (oidMap == null) {
			return null;
		}
		
		list = new ArrayList<>();
		
		for (String oid : oids) {
			list.add(oidMap.get(oid));
		}
		
		return list;
	}
	
	public JSONObject get(long id) {
		Map<String, Map<String, Value>> indexMap = this.idxIndex.get(id);
		
		if (indexMap == null) {
			return null;
		}
		
		Map<String, Value> oidMap;
		Value value;
		JSONObject
			resourceData = new JSONObject(),
			indexData;
			
		for (String index : indexMap.keySet()) {
			oidMap = indexMap.get(index);
			
			indexData = new JSONObject();
			
			resourceData.put(index, indexData);
			
			for (String oid: oidMap.keySet()) {
				value = oidMap.get(oid);
				
				indexData.put(oid, new JSONObject()
					.put("value", value.value)
					.put("timestamp", value.timestamp)
					.put("limit", value.limit)
					.put("critical", value.critical));
			}
		}
		
		return resourceData;
	}
	
	public JSONObject get() {
		Map<String, Map<String, Value>> oidMap;
		Map<String, Value> indexMap;
		Value value;
		JSONObject resourceData = new JSONObject();
		JSONObject oidData;
		
		for (long id : this.oidIndex.keySet()) {
			oidMap = this.oidIndex.get(id);
		
			oidData = new JSONObject();
			
			resourceData.put(Long.toString(id), oidData);
			
			for (String oid : oidMap.keySet()) {
				indexMap = oidMap.get(oid);
				
				for (String index: indexMap.keySet()) {
					value = indexMap.get(index);
					
					oidData.put(oid, new JSONObject()
						.put("index", index)
						.put("value", value.value)
						.put("timestamp", value.timestamp)
						.put("limit", value.limit)
						.put("critical", value.critical));
				}
			}
		}
		
		return resourceData;
	}
	
	public void clear() {
		for (Rollable r : this.rollables) {
			r.clear();
		}
	}
	
	public interface ForEach {
		public void forEach(String index, Value value);
	}
	
	public interface ForEachRollable {
		public void forEach(Rollable rollable);
	}
}
