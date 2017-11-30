import java.io.File;
import java.io.FileOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.zip.CRC32;

public class Receiver {
	static int pkt_size = 1000;
	
	
	public Receiver(int sk2_dst_port, int sk3_dst_port, String path) {
		DatagramSocket sk2, sk3;
		System.out.println(">> port_to_send=" + sk2_dst_port + ", " + "port_to_recive=" + sk3_dst_port + ".");
		
		int prevSeqNum = -1;				
		int nextSeqNum = 0;					
		boolean isTransferComplete = false;	
		
		// create sockets
		try {
			sk2 = new DatagramSocket(sk2_dst_port);	// incoming channel
			sk3 = new DatagramSocket();				// outgoing channel
			System.out.println(">> Listening");
			try {
				byte[] in_data = new byte[pkt_size];									
				DatagramPacket in_pkt = new DatagramPacket(in_data,	in_data.length);	
				InetAddress dst_addr = InetAddress.getByName("127.0.0.1");
				
				FileOutputStream fos = null;
				
				path = ((path.substring(path.length()-1)).equals("/"))? path: path + "/";	// append slash if missing
				File filePath = new File(path);
				if (!filePath.exists()) filePath.mkdir();
				
				
				while (!isTransferComplete) {
					// receive packet
					sk2.receive(in_pkt);

					byte[] received_checksum = copyOfRange(in_data, 0, 8);
					CRC32 checksum = new CRC32();
					checksum.update(copyOfRange(in_data, 8, in_pkt.getLength()));
					byte[] calculated_checksum = ByteBuffer.allocate(8).putLong(checksum.getValue()).array();
					
					// if packet is not corrupted
					if (Arrays.equals(received_checksum, calculated_checksum)){
						int seqNum = ByteBuffer.wrap(copyOfRange(in_data, 8, 12)).getInt();
						System.out.println(">> Received sequence number: " + seqNum);
						
						if (seqNum == nextSeqNum){
						
							if (in_pkt.getLength() == 12){
								byte[] ackPkt = generatePacket(-2);	
								for (int i=0; i<20; i++) sk3.send(new DatagramPacket(ackPkt, ackPkt.length, dst_addr, sk3_dst_port));
								isTransferComplete = true;			// set flag to true
								System.out.println(">> All packets received! File Created!");
								continue;	
							}
							// else send ack
							else{
								byte[] ackPkt = generatePacket(seqNum);
								sk3.send(new DatagramPacket(ackPkt, ackPkt.length, dst_addr, sk3_dst_port));
								System.out.println(">> Sent Ack " + seqNum);
							}
							
							// if first packet of transfer
							if (seqNum==0 && prevSeqNum==-1){
								int fileNameLength = ByteBuffer.wrap(copyOfRange(in_data, 12, 16)).getInt();	
								String fileName = new String(copyOfRange(in_data, 16, 16 + fileNameLength));	
								System.out.println(">> fileName length: " + fileNameLength + ", fileName:" + fileName);
								
								// create file
								File file = new File(path + fileName);
								if (!file.exists()) file.createNewFile();
								
								// init fos
								fos = new FileOutputStream(file);
								
								// write initial data to fos
								fos.write(in_data, 16 + fileNameLength, in_pkt.getLength() - 16 - fileNameLength);
							}
							
							// else if not first packet write to FileOutputStream
							else fos.write(in_data, 12, in_pkt.getLength() - 12);
							
							nextSeqNum ++; 			// update nextSeqNum
							prevSeqNum = seqNum;	
						}
						
						
						else{
							byte[] ackPkt = generatePacket(prevSeqNum);
							sk3.send(new DatagramPacket(ackPkt, ackPkt.length, dst_addr, sk3_dst_port));
							System.out.println("Receiver: Sent duplicate Ack " + prevSeqNum);
						}
					}
					
					// else packet is corrupted
					else{
						System.out.println(">> Corrupt packet dropped");
						byte[] ackPkt = generatePacket(prevSeqNum);
						sk3.send(new DatagramPacket(ackPkt, ackPkt.length, dst_addr, sk3_dst_port));
						System.out.println(">> Sent duplicate Ack " + prevSeqNum);
					}
				}
				if (fos != null) fos.close();
			} catch (Exception e) {
				e.printStackTrace();
				System.exit(-1);
			} finally {
				sk2.close();
				sk3.close();
				System.out.println(">> sk2 closed!");
				System.out.println(">> sk3 closed!");
			}
		} catch (SocketException e1) {
			e1.printStackTrace();
		}
	}
	
	// generate Ack packet
	public byte[] generatePacket(int ackNum){
		byte[] ackNumBytes = ByteBuffer.allocate(4).putInt(ackNum).array();
		// calculate checksum
		CRC32 checksum = new CRC32();
		checksum.update(ackNumBytes);
		// construct Ack packet
		ByteBuffer pktBuf = ByteBuffer.allocate(12);
		pktBuf.put(ByteBuffer.allocate(8).putLong(checksum.getValue()).array());
		pktBuf.put(ackNumBytes);
		return pktBuf.array();
	}
	
	
	public byte[] copyOfRange(byte[] srcArr, int start, int end){
		int length = (end > srcArr.length)? srcArr.length-start: end-start;
		byte[] destArr = new byte[length];
		System.arraycopy(srcArr, start, destArr, 0, length);
		return destArr;
	}
	
	// main function
	public static void main(String[] args) {
		
		if (args.length != 3) {
			System.err.println("Argument Missing");
			System.err.println("run like following: java Receiver port_to_send port_to_recive outputFolderPath");
			System.exit(-1);
		}
		else new Receiver(Integer.parseInt(args[0]), Integer.parseInt(args[1]), args[2]);
	}
}