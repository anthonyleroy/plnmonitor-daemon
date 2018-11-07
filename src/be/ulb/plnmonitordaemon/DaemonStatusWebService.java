
package be.ulb.plnmonitordaemon;

import java.net.Authenticator;
import java.net.PasswordAuthentication;
import java.net.URL;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.ws.Service;
import javax.xml.ws.WebServiceException;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.lockss.ws.entities.AuWsResult;
import org.lockss.ws.entities.PeerWsResult;
import org.lockss.ws.entities.PlatformConfigurationWsResult;
import org.lockss.ws.entities.RepositorySpaceWsResult;
import org.lockss.ws.entities.RepositoryWsResult;
import org.lockss.ws.status.DaemonStatusService;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;


/**
 * The Class DaemonStatusWebService.
 * 
 * Retrieves all available status info from all LOCKSS boxes in the network (found via initialV3PeerList in LOCKSS lockss.xml network config file 
 * via the DaemonStatusWebService with debug user access to LOCKSS web user interface 
 * 
 * Stores all info in a PostgreSQL DB. The db is configured with trigger functions to automatically keep all history after an upsert.
 * <p>
 * <h2>Known issues:</h2> 
 * <h3>1. SOAP access to LOCKSS boxes in SSL mode. </h3>
 * While the Daemon Status web service is directly accessible with basic authentication, when SSL is switched on, the authentication is done with a form-based login webpage.
 * Tried to pass the username and password directly in the header of the WSDL request but it didn’t work. 
 * Also tried implementing an Apache HTTPclient to initiate and maintain the authenticated connection for the web service requests but, still, without success.
 * Given that the access control mechanism will be considerably improved in LAAWS (Shibboleth), more useful to focus on the other plnmonitor features
 *
 * <h3>2. Dumb usage of the database</h3>
 * It would be much more efficient to store only status information transitions and not every status value
 * 
 * @author Anthony Leroy
 * @version 0.7
 */
public class DaemonStatusWebService {
	
	/** The Constant DB_DRIVER. Hardcoded parameter of postgresql driver */
	private static final String DB_DRIVER = "org.postgresql.Driver";
	
	/** The Constant DB_CONNECTION. Hardcoded parameter of plnmonitor DB connection to localhost*/
	private static final String DB_CONNECTION = "jdbc:postgresql://127.0.0.1:5432/plnmonitor";
	
	/** The Constant DB_USER. Hardcoded plnmonitor db username*/
	private static final String DB_USER = "plnmonitor";
	
	/** The Constant DB_PASSWORD. Hardcoded plnmonitor db password*/
	private static final String DB_PASSWORD = "plnmonitor";

	//TODO: use username from the database for specific box instead of hardcoded value 
	/** The Constant USER_NAME. Hardcoded daemon UI user name with debug info access only (read) for all lockss boxes in the network (8081)*/
	private static final String USER_NAME = "debug";

	//TODO: use password from the database for specific box instead of hardcoded value
	/** The Constant PASSWORD. Hardcoded daemon UI debug userpassword with debug info access only (read) for all lockss boxes in the network (8081)*/
	private static final String PASSWORD = "debuglockss";
	
	//TODO: use protocol from the database for specific box
	/** The Constant prefixDSS. Http prefix for Daemon Status Web Service URL*/
	private static final String prefixDSS = "http://";
	
	//TODO: use protocol from the database for specific box
	/** The Constant prefixSDSS. Https prefix for Daemon Status Web Service URL*/
	private static final String prefixSDSS = "https://";

	//TODO: use DSS parameters from the database for specific box (no hardcoded 8081 port but instead specific to box)
	/** The Constant postfixDSS. Postfix for Daemon Status Web Service URL */
	private static final String postfixDSS = ":8081/ws/DaemonStatusService?wsdl";
	
	/** The Constant TARGET_NAMESPACE. Daemon Status Service namespace  */
	private static final String TARGET_NAMESPACE = "http://status.ws.lockss.org/";
	
	/** The Constant SERVICE_NAME. Daemon Status Service service name*/
	private static final String SERVICE_NAME = "DaemonStatusServiceImplService";
	
	/** The Constant QUERY. Daemon Status Service query to get all available info in specific order*/
	private static final String QUERY = "select auId, name, volume, pluginName, tdbYear, accessType, contentSize, diskUsage, recentPollAgreement, tdbPublisher, availableFromPublisher, substanceState, creationTime, crawlProxy, crawlWindow, crawlPool, lastCompletedCrawl, lastCrawl, lastCrawlResult, lastCompletedPoll, lastPollResult, currentlyCrawling, currentlyPolling, subscriptionStatus, auConfiguration, newContentCrawlUrls, urlStems, isBulkContent, peerAgreements";

	/** The Constant IPV4_PATTERN. Grep expression to identify IP address of LOCKSS boxes in the LOCKSS xml file*/
	private static final String IPV4_PATTERN = 
			"(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)";

 
	/**
	 * Gets URLs of the lockss.xml configuration file for each LOCKSS network available in the database 
	 * 
	 *
	 * @return returns url for lockss.xml config file for each LOCKSS network in a List (LOCKSS network ID, lockss.xml URL)
	 * @throws SQLException the SQL exception
	 */
	public HashMap<Integer, String> getPLNConfigurationFiles() throws SQLException{
		Connection dbConnection = null;
		HashMap<Integer,String> configurationFiles = new HashMap<Integer,String>(); 

		try {							
			String configUrlQuery = "SELECT config_url, id from plnmonitor.pln";

			dbConnection = getDBConnection();

			Statement stmt = dbConnection.createStatement();
			ResultSet rs = stmt.executeQuery(configUrlQuery);
			while (rs.next()) {
				configurationFiles.put(rs.getInt("id"),rs.getString("config_url"));
			}

		} catch (SQLException e) {
			System.out.println(e.getMessage());
		} finally {
			if (dbConnection != null) {
				dbConnection.close();
			}
		}
		return (configurationFiles);
	}


	/**
	 * Load PLN configuration
	 *
	 * Return IP addresses of all the boxes in the LOCKSS network based on 
	 * the lockss.xml configuration file initialV3PeerList section
	 * 
	 *  
	 * @param plnID the pln ID in the database
	 * @param configUrl the URL of the lockss.xml file for the given LOCKSS network
	 * @return plnMembers : the list of box IP addresses in the network 
	 */
	// put all pln members IP address in plnMembers
	public List<String> loadPLNConfiguration(Integer plnID, String configUrl){
		List<String> plnMembers=new  ArrayList<String>();

		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setNamespaceAware(true);
		try{
			DocumentBuilder db = factory.newDocumentBuilder();

			Document doc = db.parse(new URL(configUrl).openStream());
			doc.getDocumentElement().normalize();
			NodeList propertyList = doc.getElementsByTagName("property");
			for (int temp = 0; temp < propertyList.getLength(); temp++) {
				Node propertyNode = propertyList.item(temp);
				if (propertyNode.getNodeType() == Node.ELEMENT_NODE) {

					Element eElement = (Element) propertyNode;

					if (eElement.getAttribute("name").contains("id.initialV3PeerList")) {
						NodeList valuesList = eElement.getElementsByTagName("value");
						for (int i=0; i<valuesList.getLength(); i++) {
							System.out.println("Value : " + valuesList.item(i).getTextContent());
							Pattern p = Pattern.compile(IPV4_PATTERN);
							Matcher m = p.matcher(valuesList.item(i).getTextContent());
							while (m.find()) {
								plnMembers.add(m.group()) ;
							}
						}
					}
				}
			}

		}
		catch(Exception e){
			System.out.println(e.toString());

		}
		return (plnMembers);
	}

	/**
	 * Load daemon status.
	 *
	 * Get all available status info from a LOCKSS box identified by its IP address boxIpAddress:
	 * - Archival Units info in the box
	 * - Repository Space from the box
	 * - Peers of the box
	 * - Repository info from the box
	 *
	 * @param plnID the pln ID in the database
	 * @param boxIpAddress the box ip address
	 * @throws SQLException the SQL exception
	 */
	public void loadDaemonStatus(Integer plnID, String boxIpAddress) throws SQLException{
		Connection dbConnection = null;
		PreparedStatement preparedStatement=null;
		Service service = null;
		List<AuWsResult> ausFromCurrentBox = null;                  
		List<RepositorySpaceWsResult> repositoryBox = null;
		List<PeerWsResult> peersBox = null;
		List<RepositoryWsResult> repo = null;
		PlatformConfigurationWsResult boxConfiguration = null;
		Integer boxId=null;
		Map<String, String> headers = null;

		try {
			// Call the service and get the results of the query.
			// Store AUs results for each server in a Hashmap (server name, list of Aus)
			this.authenticate(); //basic authentication (inline)

			String boxHostname = boxIpAddress;
			
			//ugly fix for UGent - please ignore this
			if (boxIpAddress.matches("157.193.230.142")) {
				boxHostname = "shaw.ugent.be";
			}
			String serviceAddress=prefixDSS+boxHostname+postfixDSS; 

			try {

				service = Service.create(new URL(serviceAddress), new QName(
						TARGET_NAMESPACE, SERVICE_NAME));

			}

			catch (WebServiceException e) {
				System.out.println(e.toString());
				System.out.println("Nothing to do connnection unavailable...");
			}

			try {
				if (service != null) {  //if service available, get all data from the LOCKSS box
					DaemonStatusService dss = service.getPort(DaemonStatusService.class);
					boxConfiguration = dss.getPlatformConfiguration();
					repositoryBox = dss.queryRepositorySpaces("select *");
					ausFromCurrentBox = dss.queryAus(QUERY);
					peersBox = dss.queryPeers("select *");
					repo = dss.queryRepositories("select *");
				}
			}
			catch (WebServiceException e) {
				System.out.println(e.toString());
			}

			// if data from plaftorm configuration is available, update the LOCKSS box table accordingly in the database
			if (boxConfiguration!=null){
				//update LOCKSS box config in the LOCKSS_box database
				//upsert: if box date identified by (ipaddress+pln id) is already in the database, update entry otherwise insert 
				//upsert is not available in Postgres 9.4
				try {

					String insertTableSQL = 
							"WITH upsert AS " +
									"(UPDATE plnmonitor.lockss_box " +
									"SET uiport = ?, " +
									"groups = ?, " +
									"v3identity = ?, " +
									"uptime = ?, " +
									"admin_email = ?, " +
									"disks = ?, " +
									"\"current_time\" = ?, " +
									"daemon_full_version = ?, " +
									"java_version = ?, " +
									"platform = ? " +
									"WHERE ipaddress=? and pln=? RETURNING *), " +

							"inserted AS ("+
							"INSERT INTO plnmonitor.lockss_box " +
							"(ipaddress,uiport,pln,groups,v3identity,uptime,admin_email,disks,\"current_time\", daemon_full_version, java_version, platform) "+
							"SELECT ?,?,?,?,?,?,?,?,?,?,?,? WHERE NOT EXISTS "+
							"(SELECT * FROM upsert) "+
							"RETURNING *) "+
							"SELECT * " +
							"FROM upsert " +
							"union all " +
							"SELECT * " +
							"FROM inserted";

					dbConnection = getDBConnection();
					preparedStatement = dbConnection.prepareStatement(insertTableSQL, Statement.KEEP_CURRENT_RESULT);
					preparedStatement.setString(1, "8081");
					preparedStatement.setString(2, boxConfiguration.getGroups().get(0).replaceAll("\\[|\\]", ""));
					preparedStatement.setString(3, boxConfiguration.getV3Identity().replaceAll("\\[|\\]", ""));
					preparedStatement.setLong(4, boxConfiguration.getUptime());
					preparedStatement.setString(5, boxConfiguration.getAdminEmail().replaceAll("\\[|\\]", ""));
					preparedStatement.setString(6, boxConfiguration.getDisks().get(0).replaceAll("\\[|\\]", ""));
					preparedStatement.setLong(7, boxConfiguration.getCurrentTime());
					preparedStatement.setString(8, boxConfiguration.getDaemonVersion().toString().replaceAll("\\[|\\]", ""));
					preparedStatement.setString(9, boxConfiguration.getJavaVersion().toString().replaceAll("\\[|\\]", ""));
					preparedStatement.setString(10, boxConfiguration.getPlatform().toString().replaceAll("\\[|\\]", ""));

					preparedStatement.setString(11, boxIpAddress);
					preparedStatement.setInt(12, plnID);

					preparedStatement.setString(13, boxIpAddress);
					preparedStatement.setString(14, "8081");
					preparedStatement.setInt(15, plnID);
					preparedStatement.setString(16, boxConfiguration.getGroups().get(0).replaceAll("\\[|\\]", ""));
					preparedStatement.setString(17, boxConfiguration.getV3Identity().replaceAll("\\[|\\]", ""));
					preparedStatement.setLong(18, boxConfiguration.getUptime());
					preparedStatement.setString(19, boxConfiguration.getAdminEmail().replaceAll("\\[|\\]", ""));
					preparedStatement.setString(20, boxConfiguration.getDisks().get(0).replaceAll("\\[|\\]", ""));
					preparedStatement.setLong(21, boxConfiguration.getCurrentTime());
					preparedStatement.setString(22, boxConfiguration.getDaemonVersion().toString().replaceAll("\\[|\\]", ""));
					preparedStatement.setString(23, boxConfiguration.getJavaVersion().toString().replaceAll("\\[|\\]", ""));
					preparedStatement.setString(24, boxConfiguration.getPlatform().toString().replaceAll("\\[|\\]", ""));
					ResultSet rs=preparedStatement.executeQuery();
					if (rs.next()) {
						boxId = rs.getInt("id");
					}
					System.out.println("Entry for pln: "+ plnID + " with IP address "+boxIpAddress + " ----- " + boxConfiguration.getIpAddress() + "V3 identity:" +  boxConfiguration.getV3Identity() + " is inserted/updated into LOCKSS_BOX table at position "+ boxId);

				} catch (SQLException e) {
					System.out.println(e.getMessage());

				} finally {
					if (preparedStatement != null) {
						preparedStatement.close();
					}
					if (dbConnection != null) {
						dbConnection.close();
					}
				}	
			}


			// if repository box data is collected for the current LOCKSS Box identified by box id and repository_space_lockss_id
			// insert the results in the table lockss_box_data_current

			if (repositoryBox != null) {
				for (RepositorySpaceWsResult currentBoxResult : repositoryBox) {
					try {							
						String insertTableSQL = "WITH upsert AS (UPDATE plnmonitor.lockss_box_data_current " +
								"SET used = ?, " +
								"size = ?, " +
								"free = ?, " +
								"percentage = ?, " +
								"active_aus = ?, " +
								"deleted_aus = ?, " +
								"inactive_aus = ?, " +
								"orphaned_aus = ? " +
								"WHERE box=? and repository_space_lockss_id=? RETURNING *)" +
								"INSERT INTO plnmonitor.lockss_box_data_current" +
								"(box,used,size,free,percentage,active_aus, repository_space_lockss_id, deleted_aus, inactive_aus, orphaned_aus) "+
								"SELECT ?,?,?,?,?,?,?,?,?,? WHERE NOT EXISTS "+
								"(SELECT * FROM upsert)";

						dbConnection = getDBConnection();
						preparedStatement = dbConnection.prepareStatement(insertTableSQL);
						preparedStatement.setLong(1, currentBoxResult.getUsed());
						preparedStatement.setLong(2, currentBoxResult.getSize());
						preparedStatement.setLong(3, currentBoxResult.getFree());
						preparedStatement.setDouble(4, currentBoxResult.getPercentageFull());
						preparedStatement.setLong(5, currentBoxResult.getActiveCount());
						preparedStatement.setLong(6, currentBoxResult.getDeletedCount());
						preparedStatement.setLong(7, currentBoxResult.getInactiveCount());
						preparedStatement.setLong(8, currentBoxResult.getOrphanedCount());

						preparedStatement.setLong(9, boxId );
						preparedStatement.setString(10, currentBoxResult.getRepositorySpaceId());

						preparedStatement.setLong(11, boxId );
						preparedStatement.setLong(12, currentBoxResult.getUsed());
						preparedStatement.setLong(13, currentBoxResult.getSize());
						preparedStatement.setLong(14, currentBoxResult.getFree());
						preparedStatement.setDouble(15, currentBoxResult.getPercentageFull());
						preparedStatement.setLong(16, currentBoxResult.getActiveCount());
						preparedStatement.setString(17, currentBoxResult.getRepositorySpaceId());
						preparedStatement.setLong(18,currentBoxResult.getDeletedCount());
						preparedStatement.setLong(19,currentBoxResult.getInactiveCount());
						preparedStatement.setLong(20, currentBoxResult.getOrphanedCount());

						System.out.println(preparedStatement.toString());
						preparedStatement.executeUpdate();

						System.out.println("Record is inserted and updated into database table LOCKSS_box_data_current for boxId" + boxId + " Repository Id: " + currentBoxResult.getRepositorySpaceId());

					} catch (SQLException e) {

						System.out.println(e.getMessage());

					} finally {

						if (preparedStatement != null) {
							preparedStatement.close();
						}

						if (dbConnection != null) {
							dbConnection.close();
						}

					}

				}


				// if peers box data is collected from the LOCKSS Box, insert the results in the table Peers

				if (peersBox != null) {

					for (PeerWsResult currentPeer : peersBox) {

						try {							
							String insertTableSQL = "WITH upsert AS (UPDATE plnmonitor.peer " +
									"SET box = ?, " +
									"last_poll = ?, " +
									"polls_called = ?, " +
									"last_invitation = ?, " +
									"last_vote = ?, " +
									"peer_lockss_id = ?, " +
									"last_message = ?, " +
									"invitation_count = ?, " +
									"message_count = ?, " +
									"message_type = ?, " +
									"polls_rejected = ?, " +
									"votes_cast = ? " + 
									"WHERE box=? and peer_lockss_id=? RETURNING *)" +
									"INSERT INTO plnmonitor.peer " +
									"(box,last_poll,polls_called,last_invitation,last_vote,peer_lockss_id,last_message, invitation_count, message_count, message_type, polls_rejected, votes_cast) "+
									"SELECT " +
									"?,?,?,?,? ,?,?,?,?,? ,?,? WHERE NOT EXISTS "+
									"(SELECT * FROM upsert)";

							dbConnection = getDBConnection();
							preparedStatement = dbConnection.prepareStatement(insertTableSQL);
							preparedStatement.setLong(1, boxId);
							preparedStatement.setLong(2, currentPeer.getLastPoll());
							preparedStatement.setLong(3, currentPeer.getPollsCalled());
							preparedStatement.setLong(4, currentPeer.getLastInvitation());
							preparedStatement.setLong(5, currentPeer.getLastVote());
							preparedStatement.setString(6, currentPeer.getPeerId());
							preparedStatement.setLong(7, currentPeer.getLastMessage());
							preparedStatement.setLong(8, currentPeer.getInvitationCount());
							preparedStatement.setLong(9, currentPeer.getMessageCount());
							preparedStatement.setString(10, currentPeer.getMessageType());
							preparedStatement.setLong(11, currentPeer.getPollsRejected());
							preparedStatement.setLong(12, currentPeer.getVotesCast() );

							preparedStatement.setLong(13, boxId );
							preparedStatement.setString(14, currentPeer.getPeerId() );

							preparedStatement.setLong(15, boxId);
							preparedStatement.setLong(16, currentPeer.getLastPoll());
							preparedStatement.setLong(17, currentPeer.getPollsCalled());
							preparedStatement.setLong(18, currentPeer.getLastInvitation());
							preparedStatement.setLong(19, currentPeer.getLastVote());
							preparedStatement.setString(20, currentPeer.getPeerId());
							preparedStatement.setLong(21, currentPeer.getLastMessage());
							preparedStatement.setLong(22, currentPeer.getInvitationCount());
							preparedStatement.setLong(23, currentPeer.getMessageCount());
							preparedStatement.setString(24, currentPeer.getMessageType());
							preparedStatement.setLong(25, currentPeer.getPollsRejected());
							preparedStatement.setLong(26, currentPeer.getVotesCast() );
							//preparedStatement.setInt(28, currentPeer.getPeerId().hashCode() );

							System.out.println(preparedStatement.toString());
							preparedStatement.executeUpdate();

							System.out.println("Record is inserted into Peers table!");

						} catch (SQLException e) {

							System.out.println(e.getMessage());

						} finally {

							if (preparedStatement != null) {
								preparedStatement.close();
							}

							if (dbConnection != null) {
								dbConnection.close();
							}

						}
					}

					// if AUs box data is collected from the LOCKSS Box, insert the results in the table AU_current

					if (ausFromCurrentBox != null) {
						for (AuWsResult currentAU :  ausFromCurrentBox) {
							try {							
								String insertTableSQL = "WITH upsert AS (UPDATE plnmonitor.au_current " +
										"SET box = ?, " +
										"name = ?, " +
										"plugin_name = ?, " +
										"tdb_year = ?, " +
										"access_type = ?, " +
										"content_size = ?, " +
										"recent_poll_agreement = ?, " +
										"creation_time = ?, " +
										"au_lockss_id = ?, " +
										"tdb_publisher = ?, " +
										"volume = ?, " + 
										"disk_usage = ?, " + 
										"last_completed_crawl = ?, " + 
										"last_completed_poll = ?, " + 
										"last_crawl = ?, " + 
										"last_poll = ?, " + 
										"crawl_pool = ?, " + 
										"crawl_proxy = ? ," + 
										"crawl_window = ?, " + 
										"last_crawl_result = ?, " + 
										"last_poll_result = ?, " + 
										"publishing_platform = ?, " + 
										"repository_path = ?, " + 
										"subscription_status = ?, " +
										"substance_state = ?, " + 
										"available_from_publisher = ? " + 
										"WHERE box=? and au_lockss_id=? RETURNING *)" +
										"INSERT INTO plnmonitor.au_current " +
										"(box,name,plugin_name,tdb_year,access_type,content_size,recent_poll_agreement,creation_time,au_lockss_id,tdb_publisher,volume,disk_usage,last_completed_crawl,last_completed_poll,last_crawl,last_poll,crawl_pool,crawl_proxy,crawl_window,last_crawl_result,last_poll_result,publishing_platform,repository_path,subscription_status,substance_state,available_from_publisher)" + 
										"SELECT " +
										"?,?,?,?,? ,?,?,?,?,? ,?,?,?,?,?, ?,?,?,?,?, ?,?,?,?,?, ? WHERE NOT EXISTS "+
										"(SELECT * FROM upsert)";

								dbConnection = getDBConnection();
								preparedStatement = dbConnection.prepareStatement(insertTableSQL);
								preparedStatement.setLong(1, boxId);
								preparedStatement.setString(2, currentAU.getName());
								preparedStatement.setString(3, currentAU.getPluginName());
								preparedStatement.setString(4, currentAU.getTdbYear());
								preparedStatement.setString(5, currentAU.getAccessType());
								preparedStatement.setLong(6, currentAU.getContentSize());
								preparedStatement.setDouble(7, (currentAU.getRecentPollAgreement()!=null)?currentAU.getRecentPollAgreement():0);
								preparedStatement.setLong(8, currentAU.getCreationTime());
								preparedStatement.setString(9, currentAU.getAuId());
								preparedStatement.setString(10, currentAU.getTdbPublisher());
								preparedStatement.setString(11, currentAU.getVolume());
								preparedStatement.setLong(12, (currentAU.getDiskUsage()!=null)?currentAU.getDiskUsage():0);
								preparedStatement.setLong(13, (currentAU.getLastCompletedCrawl()!=null)?currentAU.getLastCompletedCrawl():0);
								preparedStatement.setLong(14, (currentAU.getLastCompletedPoll()!=null)? currentAU.getLastCompletedPoll():0);
								preparedStatement.setLong(15, (currentAU.getLastCrawl()!=null)? currentAU.getLastCrawl():0);
								preparedStatement.setLong(16, (currentAU.getLastPoll()!= null)?currentAU.getLastPoll():0);
								preparedStatement.setString(17, currentAU.getCrawlPool());
								preparedStatement.setString(18, (currentAU.getCrawlProxy()!=null)?currentAU.getCrawlProxy():"");
								preparedStatement.setString(19, (currentAU.getCrawlWindow()!=null)?currentAU.getCrawlWindow():"");
								preparedStatement.setString(20, currentAU.getLastCrawlResult());
								preparedStatement.setString(21, currentAU.getLastPollResult());
								preparedStatement.setString(22, currentAU.getPublishingPlatform());
								preparedStatement.setString(23, (currentAU.getRepositoryPath()!=null)?currentAU.getRepositoryPath():"");
								preparedStatement.setString(24, (currentAU.getSubscriptionStatus()!=null)?currentAU.getSubscriptionStatus(): "");
								preparedStatement.setString(25, currentAU.getSubstanceState());
								preparedStatement.setBoolean(26, currentAU.getAvailableFromPublisher());

								preparedStatement.setLong(27, boxId);
								preparedStatement.setString(28, currentAU.getAuId());

								preparedStatement.setLong(29, boxId);
								preparedStatement.setString(30, currentAU.getName());
								preparedStatement.setString(31, currentAU.getPluginName());
								preparedStatement.setString(32, currentAU.getTdbYear());
								preparedStatement.setString(33, currentAU.getAccessType());
								preparedStatement.setLong(34, currentAU.getContentSize());
								preparedStatement.setDouble(35, (currentAU.getRecentPollAgreement()!=null)?currentAU.getRecentPollAgreement():0);
								preparedStatement.setLong(36, currentAU.getCreationTime());
								preparedStatement.setString(37, currentAU.getAuId());
								preparedStatement.setString(38, currentAU.getTdbPublisher());
								preparedStatement.setString(39, currentAU.getVolume());
								preparedStatement.setLong(40, currentAU.getDiskUsage());
								preparedStatement.setLong(41, currentAU.getLastCompletedCrawl());
								preparedStatement.setLong(42, (currentAU.getLastCompletedPoll()!=null)? currentAU.getLastCompletedPoll():0);
								preparedStatement.setLong(43, currentAU.getLastCrawl());
								preparedStatement.setLong(44, (currentAU.getLastPoll()!= null)?currentAU.getLastPoll():0);
								preparedStatement.setString(45, currentAU.getCrawlPool());
								preparedStatement.setString(46, (currentAU.getCrawlProxy()!=null)?currentAU.getCrawlProxy():"");
								preparedStatement.setString(47, (currentAU.getCrawlWindow()!=null)?currentAU.getCrawlWindow():"");
								preparedStatement.setString(48, currentAU.getLastCrawlResult());
								preparedStatement.setString(49, currentAU.getLastPollResult());
								preparedStatement.setString(50, currentAU.getPublishingPlatform());
								preparedStatement.setString(51, (currentAU.getRepositoryPath()!=null)?currentAU.getRepositoryPath():"");
								preparedStatement.setString(52, (currentAU.getSubscriptionStatus()!=null)?currentAU.getSubscriptionStatus(): "");
								preparedStatement.setString(53, currentAU.getSubstanceState());
								preparedStatement.setBoolean(54, currentAU.getAvailableFromPublisher());

								preparedStatement.executeUpdate();

								System.out.println("Record "+ currentAU.getName() + "is inserted into AU_current table!");

							} catch (SQLException e) {

								System.out.println(e.getMessage());

							} finally {

								if (preparedStatement != null) {
									preparedStatement.close();
								}

								if (dbConnection != null) {
									dbConnection.close();
								}

							}
						}
					}


				}

			}


		}
		catch (Exception e)
		{
			e.printStackTrace() ;
		}

	}



	/**
	 * Gets the DB connection.
	 *
	 * @return the DB connection
	 */
	private static Connection getDBConnection() {

		Connection dbConnection = null;

		try {

			Class.forName(DB_DRIVER);

		} catch (ClassNotFoundException e) {

			System.out.println(e.getMessage());

		}

		try {

			dbConnection = DriverManager.getConnection(
					DB_CONNECTION, DB_USER,DB_PASSWORD);
			return dbConnection;

		} catch (SQLException e) {

			System.out.println(e.getMessage());

		}

		return dbConnection;

	}

	/**
	 * Gets the current time stamp.
	 *
	 * @return the current time stamp
	 */
	private static java.sql.Timestamp getCurrentTimeStamp() {

		java.util.Date today = new java.util.Date();
		return new java.sql.Timestamp(today.getTime());

	}

	//TODO: Currently hardcoded username and password for debug user - info needs to come from DB
	/**
	 * Sets the authenticator that will be used by the networking code when the
	 * HTTP server asks for authentication.
	 */
	private void authenticate() {
		Authenticator.setDefault(new Authenticator() {
			@Override
			protected PasswordAuthentication getPasswordAuthentication() {
				return new PasswordAuthentication(USER_NAME, PASSWORD.toCharArray());
			}
		});
	}

	//TODO: Unsuccessful tentative to use form base authentication when SSL is enabled on the box
	/**
	 * Sets the authenticator that will be used by the networking code when the
	 * HTTP server asks for authentication.
	 *
	 * @param server the server
	 * @throws Exception the exception
	 */
	private void formAuthenticate(String server ) throws Exception{
		CredentialsProvider credsProvider = new BasicCredentialsProvider();
		credsProvider.setCredentials(
				new AuthScope(server, 8081),
				new UsernamePasswordCredentials("debug", "debuglockss"));
		CloseableHttpClient httpclient = HttpClients.custom()
				.setDefaultCredentialsProvider(credsProvider)
				.build();
		try {
			HttpGet httpget = new HttpGet("https://"+ server +":8081/Home");

			System.out.println("Executing request " + httpget.getRequestLine());
			CloseableHttpResponse response = httpclient.execute(httpget);
			try {
				System.out.println("----------------------------------------");
				System.out.println(response.getStatusLine());
				System.out.println(EntityUtils.toString(response.getEntity()));
			} finally {
				response.close();
			}
		} finally {
			httpclient.close();
		}
	}

}
