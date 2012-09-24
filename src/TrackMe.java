import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.regex.Pattern;

public class TrackMe {
	static final int MAX_LEVEL = 4; // maximum depth for mirroring

	static Url baseURL; // The given starting url
	static Map<String, String> downloaded, modifiedLink;
	static Set<String> savedFileList;
	static File homeDir, dbase;

	public static void main(String[] args) throws IOException {
		baseURL = new Url(args[0]);
		downloaded = new HashMap<String, String>();
		modifiedLink = new HashMap<String, String>();
		savedFileList = new HashSet<String>();

		// Create or clear local home directory
		homeDir = new File(baseURL.getHost());
		if (homeDir.exists())
			clearFolder(homeDir);
		else
			homeDir.mkdir();

		// Create dbase.txt
		dbase = new File(homeDir.getName() + "/dbase.txt");
		dbase.createNewFile();

		// Download pages up to depth MAX_LEVEL from level 0
		mirrorPage(baseURL, 0);

		// Change file references in local files

	}

	// mirror page at given url to local homeDir
	private static void mirrorPage(Url url, int level) {
		String pagePath = url.urlString.replaceFirst(url.getFile(), "");

		if (!downloaded.containsKey(url.urlString)) {
			HttpMessage inHttpMsg = retrieveFile(url);

			if (inHttpMsg.code == 200) {
				File currPage = saveFile(url, inHttpMsg);
				processPage(currPage, pagePath, level);
			} else if (inHttpMsg.code == 404) {
				write404ToDbase(inHttpMsg);
			}
		} else {
			File currPage = findLocalFile(url);

			if (currPage != null) {
				processPage(currPage, pagePath, level);
			}
		}
	}

	// process page, download images and mirror pages obtained from the
	// processing
	private static void processPage(File currPage, String pagePath, int level) {
		List<Url> pageList = new LinkedList<Url>();
		List<Url> resourceList = new LinkedList<Url>();

		parseHTML(currPage, pageList, resourceList, pagePath);
		downloadResources(resourceList);
		if (level < MAX_LEVEL) {
			for (Url u : pageList) {
				mirrorPage(u, level + 1);
			}
		}
	}

	// finds the local file for an already downloaded url
	private static File findLocalFile(Url url) {
		String localFileName = downloaded.get(url.urlString);

		if (localFileName == null)
			return null;

		File localFile = new File(homeDir.getName() + "/" + localFileName);

		if (localFile.exists())
			return localFile;
		else
			return null;
	}

	// find all html links and img links in a html file
	// saves them to pageList and resourcesList respectively
	private static void parseHTML(File currPage, List<Url> pageList,
			List<Url> resourceList, String pagePath) {
		String line;

		try {
			BufferedReader fin = new BufferedReader(new FileReader(currPage));

			// search for links line by line
			while ((line = fin.readLine()) != null) {
				findHtmlLinks(line, pageList, pagePath);
				findImageLinks(line, resourceList, pagePath);
			}

			fin.close();

		} catch (FileNotFoundException e) {
			System.out.println(currPage.getName() + " not found");
		} catch (IOException io) {

		}
	}

	// Find all the html links in a line of html and save them to pageList
	private static void findHtmlLinks(String line, List<Url> pageList,
			String pagePath) {
		int readIndex, linkIndex;
		String currLink;

		readIndex = 0;
		// detect a <a> tag starting from readIndex at the current html line
		while ((linkIndex = line.indexOf("<a href=", readIndex)) != -1) {
			// extract link from the tag
			currLink = "";
			while (line.charAt(linkIndex + 9) != '\"') {
				currLink += line.charAt(linkIndex + 9);
				linkIndex++;
			}

			Url u = makeAbsoluteURL(pagePath, currLink);

			// add to pageList if it's from the same host
			if (u.getHost().equals(baseURL.getHost()))
				pageList.add(u);

			// continue searching from after the current link
			readIndex = linkIndex + 1;
		}
	}

	// Find all the image links in a line of html and save them to resourceList
	private static void findImageLinks(String line, List<Url> resourceList,
			String pagePath) {
		int readIndex, linkIndex;
		String currLink;

		readIndex = 0;
		// detect a <img> tag starting from readIndex at the current html line
		while ((linkIndex = line.indexOf("<img src=", readIndex)) != -1) {
			// extract link from the tag
			currLink = "";
			while (line.charAt(linkIndex + 10) != '\"') {
				currLink += line.charAt(linkIndex + 10);
				linkIndex++;
			}

			Url u = makeAbsoluteURL(pagePath, currLink);

			// add to resourceList if it comes from the same server
			if (u.getHost().equals(baseURL.getHost()))
				resourceList.add(u);

			// continue searching after the current link
			readIndex = linkIndex + 1;
		}
	}

	// Change a relative url to an absolute one
	private static Url makeAbsoluteURL(String pagePath, String link) {
		String originalLink = link;
		
		if (!link.contains("http://") && link.charAt(0) != '/')
			link = pagePath + link;
		else if (!link.contains("http://") && link.charAt(0) == '/')
			link = "http://" + baseURL.getHost() + link;

		modifiedLink.put(originalLink,link);
		
		return new Url(link);
	}

	// Download all image in a page
	private static void downloadResources(List<Url> resourceList) {
		for (Url url : resourceList) {
			// Download if not already downloaded
			if (!downloaded.containsKey(url.urlString)) {
				HttpMessage inHttpMsg = retrieveFile(url);
				if (inHttpMsg.code == 200)
					saveFile(url, inHttpMsg);
				else if (inHttpMsg.code == 404)
					write404ToDbase(inHttpMsg);
			}
		}
	}

	// Decides what to save the received file as in the local directory
	private static File saveFile(Url url, HttpMessage inHttpMsg) {
		String fileName = url.getFile();

		// if file of the same name already exist in local directory
		// append x to the file name
		while (savedFileList.contains(fileName)) {
			StringTokenizer st = new StringTokenizer(fileName, ".");
			fileName = st.nextToken();
			fileName += "x";
			while (st.hasMoreTokens())
				fileName = fileName + "." + st.nextToken();
		}

		// write file to disk and record that the file has been
		// downloaded and its local filename
		File f = writeToFile(inHttpMsg, fileName);
		downloaded.put(url.urlString, fileName);
		savedFileList.add(fileName);

		return f;
	}

	// writes HTTP response body in msg to file with the fileName given
	private static File writeToFile(HttpMessage msg, String fileName) {
		File f = new File((baseURL.getHost() + "/" + fileName));

		try {
			f.createNewFile();
			if (msg.fileType == HttpMessage.HTML) {
				BufferedWriter fout = new BufferedWriter(new FileWriter(f));

				fout.write(msg.htmlContent);
				fout.close();

				fout = new BufferedWriter(new FileWriter(dbase, true));
				fout.write(msg.url.getHost() + ";" + msg.url.getPath() + ";"
						+ f.getName() + ";" + msg.lastModified + "\r\n");
				fout.close();
			} else if (msg.fileType == HttpMessage.IMAGE) {
				FileOutputStream fout = new FileOutputStream(f);
				fout.write(msg.imgContent);
				fout.close();
			}
		} catch (IOException e) {
			System.out.println("Error writing " + msg.url.getFile());
		}

		return f;
	}

	// indicate in dbase.txt that file does not exist in server
	private static void write404ToDbase(HttpMessage inHttpMsg) {
		try {
			BufferedWriter fout = new BufferedWriter(
					new FileWriter(dbase, true));
			fout.write(inHttpMsg.url.getHost() + ";" + inHttpMsg.url.getPath()
					+ ";FILE DOES NOT EXIST;null\r\n");
			fout.close();
		} catch (IOException e) {
			System.out.println("Error writing to dbase");
		}
	}

	// Sends a GET request for a specified url and returns the HTTP response
	private static HttpMessage retrieveFile(Url url) {
		HttpMessage outmsg = new HttpMessage(HttpMessage.OUTGOING, url);
		HttpMessage inmsg = new HttpMessage(HttpMessage.INCOMING, url);

		inmsg.receive(outmsg.send(), outmsg.fileType);

		return inmsg;
	}

	// Clears all files in a folder
	private static void clearFolder(File f) {
		if (f.isDirectory())
			for (File c : f.listFiles())
				c.delete();
	}

}

// Class for sending and receiving HTTP messages
class HttpMessage {
	public static final int OUTGOING = 0;
	public static final int INCOMING = 1;
	public static final int HTML = 0;
	public static final int IMAGE = 1;
	private static final String IMAGE_PATTERN = "([^\\s]+(\\.(?i)(jpeg|jpg|gif))$)";
	private static final String HTML_PATTERN = "([^\\s]+(\\.(?i)(html))$)";

	int direction;
	Url url;
	String header, htmlContent, version;
	byte imgContent[];

	String lastModified;
	int fileType, contentLength;

	int code;
	String phrase;

	public HttpMessage(int dir, Url url) {
		this.direction = dir;
		this.url = url;

		// Build GET message if direction is outgoing
		if (direction == OUTGOING) {
			version = "1.0";

			header = ("GET " + url.getPath() + " HTTP/" + version + "\r\n");
			header += ("Host: " + url.getHost() + "\r\n");

			// Declare accept types in header depending on file type
			if (Pattern.matches(HTML_PATTERN, url.getFile())) {
				header += ("Accept: text/plain, text/html, text/*\r\n");
				fileType = HTML;
			} else if (Pattern.matches(IMAGE_PATTERN, url.getFile())) {
				header += ("Accept: image/jpeg, image/jpg, image/gif\r\n");
				fileType = IMAGE;
			}
			htmlContent = "";
		}
	}

	// Receives HTTP response from the given socket connection
	public void receive(Socket socket, int fType) {
		String line;
		fileType = fType;

		try {
			InputStream sinStream = socket.getInputStream();
			BufferedReader sin = new BufferedReader(new InputStreamReader(
					sinStream));

			// Parse first line of header
			header = sin.readLine();
			StringTokenizer st = new StringTokenizer(header);
			version = st.nextToken();
			version = version.substring(5);
			code = Integer.parseInt(st.nextToken());
			phrase = st.nextToken();

			// Parse the rest of the header
			while (!(line = sin.readLine()).equalsIgnoreCase("")) {
				if (line.contains("Content-Length"))
					contentLength = Integer.valueOf(line.substring(16));
				if (line.contains("Last-Modified"))
					lastModified = line.substring(15);
				header += ("\r\n" + line);
			}

			// Download HTML files
			if (fileType == HTML) {
				htmlContent = "";
				while ((line = sin.readLine()) != null) {
					// Find the last modified date from meta tag
					if (line.contains("http-equiv=\"last-modified\"")) {
						int dateIndex = line
								.indexOf("http-equiv=\"last-modified\"");
						lastModified = line.substring(dateIndex + 36,
								dateIndex + 65);
					}

					htmlContent += ("\n" + line);
				}
			}
			// Download image files
			else if (fileType == IMAGE) {
				int n = 0;
				byte[] buffer = new byte[1024];
				ByteArrayOutputStream imgBuilder = new ByteArrayOutputStream();
				while ((n = sinStream.read(buffer)) != -1) {
					imgBuilder.write(buffer, 0, n);
				}
				imgBuilder.close();

				imgContent = imgBuilder.toByteArray();
			}

			socket.close();

		} catch (IOException e) {
			System.out.println("Failed reading from " + url.urlString);
		}
	}

	// Send HTTP request and return the socket connection
	public Socket send() {
		try {
			Socket socket = new Socket(url.getHost(), 80);

			PrintWriter sout = new PrintWriter(socket.getOutputStream(), false);
			sout.print(header);
			sout.print("\r\n");
			sout.print("");
			sout.flush();

			return socket;
		} catch (IOException e) {
			System.out
					.println("Failed sending GET request to " + url.urlString);
		}

		return null;
	}
}

// Url class for URL parsing. Only accept absolute urls
class Url {
	String urlString, host, path, file, protocol;

	public Url(String s) {
		urlString = s;

		StringTokenizer st = new StringTokenizer(s, "/", false);
		protocol = st.nextToken();
		protocol = protocol.substring(0, protocol.length() - 1);

		host = st.nextToken();

		path = "";
		while (st.hasMoreTokens()) {
			file = st.nextToken();
			path = path + "/" + file;
		}
	}

	// Returns the host name of the url
	public String getHost() {
		return host;
	}

	// Returns the path of the url
	public String getPath() {
		return path;
	}

	// Returns the file name contained in the url
	public String getFile() {
		return file;
	}

	// Return the protocol name used in the url
	public String getProtocol() {
		return protocol;
	}

	public String toString() {
		return urlString;
	}
}