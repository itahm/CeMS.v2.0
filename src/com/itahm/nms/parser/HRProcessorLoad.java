package com.itahm.nms.parser;

import java.util.Map;

import com.itahm.nms.Bean.CriticalEvent;
import com.itahm.nms.Bean.Max;
import com.itahm.nms.Bean.Value;
import com.itahm.nms.ResourceManager;

import java.util.ArrayList;
import java.util.HashMap;

public class HRProcessorLoad extends AbstractParser {
	public HRProcessorLoad(ResourceManager resourceManager) {
		super(resourceManager);
	}

	private final String OID = "1.3.6.1.2.1.25.3.3.1.2";
	
	private final Map<Long, Map<String, Integer>> load = new HashMap<>();
	
	@Override
	public ArrayList<CriticalEvent> parse(long id) {
		super.resourceManager.forEachIndex(id, OID, (index, value) -> {
			parse(id, index, value);
		});
		
		return new ArrayList<>();
	}

	public void parse(long id, String index, Value value) {
		int load;
		
		try {
			load = Integer.valueOf(value.value);
		} catch (NumberFormatException nfe) {
			return;
		}
		
		Map<String, Integer> indexMap = this.load.get(id);
			
		if (indexMap == null) {
			this.load.put(id, indexMap = new HashMap<>());
		}
		
		indexMap.put(index, load);
		
		Max max = super.max.get(id);
		
		if (max == null || max.rate < load) {
			super.max.put(id, new Max(id, index, load, load));
		}
	}
	
	public CriticalEvent parse(long id, Value value) {
		int load = Integer.valueOf(value.value);
		
		if (value.limit > 0) {
			boolean critical = load > value.limit;
			
			if (critical != value.critical) {
				value.critical = critical;
				
				return new CriticalEvent(id, "0", "1.3.6.1.4.1.49447.4", critical, String.format("프로세서 로드 %d%%", load));
			}
		} else if (value.critical) {
			value.critical = false;
				
			return new CriticalEvent(id, "0", "1.3.6.1.4.1.49447.4", false, String.format("프로세서 로드 %d%%", load));
		}
		
		return null;
	}
	
	public Integer getLoad(long id) {
		int sum = 0;
		int count = 0;
		
		Map<String, Integer> indexMap = this.load.get(id);
		
		if (indexMap == null) {
			return null;
		}
		
		for (String index : indexMap.keySet()) {
			sum += indexMap.get(index);
			
			count++;
		}
		
		return count > 0? sum / count: null;
	}
	
	@Override
	public String toString() {
		return "HRPROCESSORLOAD";
	}

}
