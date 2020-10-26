package com.itahm.nms.parser;

import com.itahm.nms.ResourceManager;

public class IFOctetsOut extends IFOctetsIn {
	
	public IFOctetsOut(ResourceManager resourceManager) {
		super(resourceManager, "1.3.6.1.2.1.2.2.1.16", new String [] {
				"1.3.6.1.4.1.49447.3.5", // BANDWIDTH
				"1.3.6.1.2.1.31.1.1.1.15", //IFHIGHSPEED
				"1.3.6.1.2.1.2.2.1.5", //IFSPEED
				"1.3.6.1.2.1.31.1.1.1.10", // IFHCOUTOCTETS
				"1.3.6.1.4.1.49447.3.2" // IFOUTBPS
			});
	}

	protected String getTitle() {
		return "송신";
	}
	
	@Override
	public String toString() {
		return "IFOUTOCTETS";
	}

}
