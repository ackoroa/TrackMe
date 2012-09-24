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
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.regex.Pattern;

public class TrackMe {
	static final int MAX_LEVEL = 4;

	static Url baseURL;
	static Set<String> downloaded, savedFileList;
	static File homeDir, dbase;

	public static void main(String[] args) throws IOException {
		baseURL = new Url(args[0]);
		downloaded = new HashSet<String>();
		savedFileList = new HashSet<String>();

		homeDir = new File(baseURL.getHost());
		if (homeDir.exists())
			clearFolder(homeDir);
		else
			homeDir.mkdir();

		dbase = new File(homeDir.getName() + "/dbase.txt");
		dbase.createNewFile();

		mirrorPage(baseURL, 0);
		// change file reference
	}

	private static void mirrorPage(Url url, int level) {
		String pagePath = url.urlString.replaceFirst(url.getFile(), "");

		if (!downloaded.contains(url.urlString)) {
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

	private static File findLocalFile(Url url) {
		String line, localFileName;
		BufferedReader fin;

		try {
			fin = new BufferedReader(new FileReader(dbase));
			while ((line = fin.readLine()) != null) {
				if (line.contains(url.getPath())) {
					StringTokenizer st = new StringTokenizer(line, ";");
					st.nextToken();
					st.nextToken();
					localFileName = st.nextToken();

					if (localFileName.equals("FILE DOES NOT EXIST"))
						break;

					File localFile = new File(url.getHost() + "/"
							+ localFileName);
					fin.close();
					return localFile;
				}
			}
			fin.close();
		} catch (IOException e) {
			System.out.println("Error reading dbase");
		}

		return null;
	}

	private static void parseHTML(File currPage, List<Url> pageList,
			List<Url> resourceList, String pagePath) {
		String line;

		try {
			BufferedReader fin = new BufferedReader(new FileReader(currPage));

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

	private static void findHtmlLinks(String line, List<Url> pageList,
			String pagePath) {
		int readIndex, linkIndex;
		String currLink;

		readIndex = 0;
		while ((linkIndex = line.indexOf("<a href=", readIndex)) != -1) {
			currLink = "";
			while (line.charAt(linkIndex + 9) != '\"') {
				currLink += line.charAt(linkIndex + 9);
				linkIndex++;
			}

			Url u = makeAbsoluteURL(pagePath, currLink);

			if (u.getHost().equals(baseURL.getHost()))
				pageList.add(u);

			readIndex = linkIndex + 1;
		}
	}
	
	private static void findImageLinks(String line, List<Url> resourceList,
			String pagePath) {
		int readIndex, linkIndex;
		String currLink;

		readIndex = 0;
		while ((linkIndex = line.indexOf("<img src=", readIndex)) != -1) {
			currLink = "";
			while (line.charAt(linkIndex + 10) != '\"') {
				currLink += line.charAt(linkIndex + 10);
				linkIndex++;
			}

			Url u = makeAbsoluteURL(pagePath, currLink);

			if (u.getHost().equals(baseURL.getHost()))
				resourceList.add(u);

			readIndex = linkIndex + 1;
		}
	}

	private static Url makeAbsoluteURL(String pagePath, String currLink) {
		if (!currLink.contains("http://") && currLink.charAt(0) != '/')
			currLink = pagePath + currLink;
		else if (!currLink.contains("http://") && currLink.charAt(0) == '/')
			currLink = "http://" + baseURL.getHost() + currLink;

		return new Url(currLink);
	}

	private static void downloadResources(List<Url> resourceList) {
		for (Url url : resourceList) {
			if (!downloaded.contains(url.urlString)) {
				HttpMessage inHttpMsg = retrieveFile(url);
				if (inHttpMsg.code == 200)
					saveFile(url, inHttpMsg);
				else if (inHttpMsg.code == 404)
					write404ToDbase(inHttpMsg);
			}
		}
	}

	private static File saveFile(Url url, HttpMessage inHttpMsg) {
		String fileName = url.getFile();
		while (savedFileList.contains(fileName)) {
			StringTokenizer st = new StringTokenizer(fileName, ".");
			fileName = st.nextToken();
			fileName += "x";
			while (st.hasMoreTokens())
				fileName = fileName + "." + st.nextToken();
		}

		File f = writeToFile(inHttpMsg, fileName);
		downloaded.add(url.urlString);
		savedFileList.add(fileName);

		return f;
	}

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

	private static HttpMessage retrieveFile(Url url) {
		HttpMessage outmsg = new HttpMessage(HttpMessage.OUTGOING, url);
		HttpMessage inmsg = new HttpMessage(HttpMessage.INCOMING, url);
		inmsg.receive(outmsg.send(), outmsg.fileType);

		return inmsg;
	}

	private static void clearFolder(File f) {
		if (f.isDirectory())
			for (File c : f.listFiles())
				c.delete();
	}

}

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

		if (direction == OUTGOING) {
			version = "1.0";

			header = ("GET " + url.getPath() + " HTTP/" + version + "\r\n");
			header += ("Host: " + url.getHost() + "\r\n");

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

	public void receive(Socket socket, int fType) {
		String line;
		fileType = fType;

		try {
			InputStream sinStream = socket.getInputStream();
			BufferedReader sin = new BufferedReader(new InputStreamReader(
					sinStream));

			header = sin.readLine();
			StringTokenizer st = new StringTokenizer(header);
			version = st.nextToken();
			version = version.substring(5);
			code = Integer.parseInt(st.nextToken());
			phrase = st.nextToken();

			while (!(line = sin.readLine()).equalsIgnoreCase("")) {
				if (line.contains("Content-Length"))
					contentLength = Integer.valueOf(line.substring(16));
				if (line.contains("Last-Modified"))
					lastModified = line.substring(15);
				header += ("\r\n" + line);
			}

			if (fileType == HTML) {
				htmlContent = "";
				while ((line = sin.readLine()) != null) {
					if (line.contains("http-equiv=\"last-modified\"")) {
						int dateIndex = line
								.indexOf("http-equiv=\"last-modified\"");
						lastModified = line.substring(dateIndex + 36,
								dateIndex + 65);
						// System.out.println(lastModified);
					}

					htmlContent += ("\n" + line);
				}
			} else if (fileType == IMAGE) {
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
					.println("Failed to send GET request to " + url.urlString);
		}

		return null;
	}
}

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

	public String getHost() {
		return host;
	}

	public String getPath() {
		return path;
	}

	public String getFile() {
		return file;
	}

	public String getProtocol() {
		return protocol;
	}

	public String toString() {
		return urlString;
	}
}