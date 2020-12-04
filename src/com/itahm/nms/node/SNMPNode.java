package com.itahm.nms.node;

import java.io.IOException;
import java.net.InetAddress;
/*import java.util.ArrayList;*/
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Vector;

import org.snmp4j.CommunityTarget;
import org.snmp4j.PDU;
import org.snmp4j.ScopedPDU;
import org.snmp4j.Snmp;
import org.snmp4j.Target;
import org.snmp4j.UserTarget;
import org.snmp4j.event.ResponseEvent;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.smi.Null;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.UdpAddress;
import org.snmp4j.smi.Variable;
import org.snmp4j.smi.VariableBinding;

import com.itahm.nms.node.PDUManager;

public class SNMPNode extends ICMPNode {

	private final static long TIMEOUT = 5000L;
	private final static int RETRY = 2;
	private final Snmp snmp;
	protected final Target<UdpAddress> target;
	private final Set<OID> reqList = new HashSet<>();
	private final Map<OID, OID> reqMap = new HashMap<>();
	private Integer code;
	
	private SNMPNode(Snmp snmp, long id, String ip, Target<UdpAddress> target) throws IOException {
		super(id, ip, String.format("SNMPNode %s", ip));
		
		this.snmp = snmp;
		this.target = target;
	}
		
	public static SNMPNode getInstance(Snmp snmp, long id, String ip, int udp, String security, int version, int level) throws IOException {
		Target<UdpAddress> target;
		
		switch(version) {
		case SnmpConstants.version3:
			target = new UserTarget<UdpAddress>(new UdpAddress(InetAddress.getByName(ip), udp), new OctetString(security), new byte [0], level);
			
			target.setVersion(version);
			target.setTimeout(TIMEOUT);
			target.setRetries(RETRY);
			
			return new SNMPNode.SNMPV3Node(snmp, id, ip, target);
		default:
			target = new CommunityTarget<>(new UdpAddress(InetAddress.getByName(ip), udp), new OctetString(security));
			
			target.setVersion(version);
			target.setTimeout(TIMEOUT);
			target.setRetries(RETRY);
			
			return new SNMPNode(snmp, id, ip, target);
		}	
	}
	
	@Override
	public void fireEvent(Object ...event) {
		if (event[0] instanceof Action && (Action)event[0] == Action.PING) {
			if (event[1] instanceof Long) {
				long rtt = (long)event[1];
				
				if (rtt > -1) {
					PDU pdu = PDUManager.requestPDU(super.id, createPDU());
					OID oid;
					
					pdu.setType(PDU.GETNEXT);
					
					this.reqList.clear();
					this.reqMap.clear();

					List<? extends VariableBinding> vbs = pdu.getVariableBindings();
					VariableBinding vb;
					
					for (int i=0, length = vbs.size(); i<length; i++) {
						vb = (VariableBinding)vbs.get(i);
					
						oid = vb.getOid();
						
						this.reqList.add(oid);
						this.reqMap.put(oid, oid);
					}
					
					try {
						int code = repeat(this.snmp.send(pdu, this.target));
						boolean issue = false;
						
						if (this.code == null) {
							this.code = code;
						} else if (this.code != code) {
							this.code = code;
							
							issue = true;
						}
						
						super.fireEvent(Action.SNMP, code, issue);
					} catch (Exception e) {
						super.fireEvent(Action.SNMP, e);
					};
				}
				
				super.fireEvent(event);
			}
		}
	}
	
	private final PDU getNextPDU(PDU request, PDU response) throws IOException {
		PDU pdu = null;
		long requestID = response.getRequestID().toLong();
		List<? extends VariableBinding> requestVBs = request.getVariableBindings();
		List<? extends VariableBinding> responseVBs = response.getVariableBindings();
		List<VariableBinding> nextRequests = new Vector<VariableBinding>();
		VariableBinding requestVB;
		VariableBinding responseVB;
		Variable value;
		OID
			initialOID,
			requestOID,
			responseOID;
		
		for (int i=0, length = responseVBs.size(); i<length; i++) {
			requestVB = requestVBs.get(i);
			responseVB = responseVBs.get(i);
			
			requestOID = requestVB.getOid();
			responseOID = responseVB.getOid();
			
			value = responseVB.getVariable();
			
			if (!value.equals(Null.endOfMibView)) {
				initialOID = this.reqMap.get(requestOID);
				
				if (responseOID.startsWith(initialOID)) {
					nextRequests.add(new VariableBinding(responseOID));
					
					this.reqMap.put(responseOID, initialOID);
					
					super.fireEvent(Action.RESOURCE, initialOID, responseOID.getSuffix(initialOID), responseVB.getVariable(), requestID);
				}
			}
			
			this.reqMap.remove(requestOID);
		}
		
		if (nextRequests.size() > 0) {
			pdu = createPDU();
			
			pdu.setVariableBindings(nextRequests);
		}
		
		return pdu;
	}
	
	// recursive method
	private int repeat(ResponseEvent<UdpAddress> event) throws IOException {
		if (event == null) {
			return SnmpConstants.SNMP_ERROR_TIMEOUT;
		}
		
		PDU response = event.getResponse();
		
		if (response == null || event.getSource() instanceof Snmp.ReportHandler) {			
			return SnmpConstants.SNMP_ERROR_TIMEOUT;
		}
		
		PDU request = event.getRequest();
		int status = response.getErrorStatus();
		
		if (status != SnmpConstants.SNMP_ERROR_SUCCESS) {
			return status;
		}
		
		PDU nextPDU = getNextPDU(request, response);
		
		if (nextPDU == null) {
			return SnmpConstants.SNMP_ERROR_SUCCESS;
		}
		
		return repeat(this.snmp.send(nextPDU, this.target));
	}
	
	protected PDU createPDU() {
		PDU pdu = new PDU();
		
		pdu.setType(PDU.GETNEXT);
		
		return pdu;
	}
	
	public static class SNMPV3Node extends SNMPNode {
		private SNMPV3Node(Snmp snmp, long id, String ip, Target<UdpAddress> target) throws IOException {
			super(snmp, id, ip, target);
		}
		
		@Override
		public PDU createPDU() {
			PDU pdu = new ScopedPDU();
			
			pdu.setType(PDU.GETNEXT);
			
			return pdu;
		}
	}
}