package com.itahm.nms.parser;

import com.itahm.nms.Bean.CriticalEvent;
import com.itahm.nms.Bean.Max;
import com.itahm.nms.Bean.Value;
import com.itahm.nms.ResourceManager;

import java.util.ArrayList;

public class ResponseTime extends AbstractParser {
	
	public ResponseTime(ResourceManager resourceManager) {
		super(resourceManager);
	}

	@Override
	public ArrayList<CriticalEvent> parse(long id) {
		ArrayList<CriticalEvent> list = new ArrayList<> ();
		
		CriticalEvent ce = parse(id, "0");
		
		if (ce != null) {
			list.add(ce);
		}
		
		return list;
	}
	
	private CriticalEvent parse(long id, String index) {
		Value value = super.resourceManager.getValue(id, "1.3.6.1.4.1.49447.1", index);
		
		if (value == null) {
			return null;
		}
		
		Long rtt;
		
		try {
			rtt = Long.valueOf(value.value);
		} catch (NumberFormatException nfe) {
			return null;
		}
		
		Max max = super.max.get(id);
		
		if (max == null || max.value < rtt) {
			super.max.put(id, new Max(id, index, rtt));
		}
		
		if (value.limit > 0) {
			boolean critical = rtt > value.limit;
		
			if (value.critical != critical) {
				value.critical = critical;
				
				return new CriticalEvent(id, index, "1.3.6.1.4.1.49447.1", critical,
					String.format("응답 시간 %dms", rtt));
			}
		} else if (value.critical) {
			value.critical = false;
			
			return new CriticalEvent(id, index, "1.3.6.1.4.1.49447.1", false,
				String.format("응답 시간 %dms", rtt));
		}
		
		return null;
	}

	
	@Override
	public String toString() {
		return "RESPONSETIME";
	}
}
