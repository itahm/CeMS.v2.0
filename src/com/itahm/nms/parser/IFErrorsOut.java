package com.itahm.nms.parser;

import com.itahm.nms.ResourceManager;

public class IFErrorsOut extends IFErrorsIn {

	public IFErrorsOut(ResourceManager resourceManager) {
		super(resourceManager, new String [] {
				"1.3.6.1.2.1.2.2.1.20",
				"1.3.6.1.4.1.49447.3.4"
			});
	}
	
	@Override
	protected String getEventTitle() {
		return "송신 오류";
	}
	
	@Override
	public String toString() {
		return "IFOUTERRORS";
	}
}
