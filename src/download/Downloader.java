package download;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.regex.Pattern;

import dao.DBConnector;
import mail.EmailHelper;
import model.LogStatus;
import model.MyLog;
import model.Process;
import model.ProcessStatus;
import utils.FileExtentionUtils;

public class Downloader {
//	duyet config dowload het cac id trong config
	public static void startDowload() throws Exception {
		Statement statement = DBConnector.loadControlConnection().createStatement();
		String sql = "SELECT id FROM `config`";
		ResultSet rs = statement.executeQuery(sql);
		while (rs.next()) {
			// duyet config dowload tung id 
			final int id = rs.getInt("id");
			// cai tien cho chay nhanh hon
			new Thread(() -> {
				try {
					startDowload(id);
				} catch (Exception e) {
					e.printStackTrace();
				}
			}).start();
		}

	}
//	dowload theo id
	public static void startDowload(int id) throws Exception {
		Statement statement = DBConnector.loadControlConnection().createStatement();
		String sql = "SELECT * FROM `config` WHERE id = " + id;

		ResultSet rs = statement.executeQuery(sql);
		if (rs.next()) {
			// kiem tra xem file dang o dau
			String sourcesStatus = rs.getString("sources_status");

			if (sourcesStatus.equals("REMOTE")) {
				downloadRemoteFiles(statement, rs);
			} else if (sourcesStatus.equals("LOCAL")) {
				processLocalFiles(statement, rs);
			}
			statement.close();
		}
	}
//	xu ly file o local
	private static void processLocalFiles(Statement statement, ResultSet rs) throws SQLException, IOException {
		int configID = rs.getInt("id");
		String localDir = rs.getString("local_dir");
		int processConfigID = rs.getInt("process_config_id");

		File local = new File(localDir);
		if (!local.exists()) {
			// file khong ton tai set log 
			MyLog log = new MyLog();
			log.setConfig_id(configID);
			log.setStatus(LogStatus.ERROR);
			log.setDownloadDT(new Timestamp(new Date().getTime()));
			log.setFilePath(local.getAbsolutePath());
			log.setComment("File is not exists");
			log.commitDownload();
		} else {
			if (local.isFile()) {
				//kiem tra xem file co thay doi khong
				if (isCheckSumHasChange(local.getAbsolutePath(), configID)) {
					MyLog log = new MyLog();
					log.setConfig_id(configID);
					log.setStatus(LogStatus.EXTRACT_READY);
					log.setDownloadDT(new Timestamp(new Date().getTime()));
					log.setFilePath(local.getAbsolutePath());
					log.commitDownload();

					// QUEUE a new process in db
					Process process = new Process();
					process.setDataConfigID(configID);
					process.setProcessConfigID(processConfigID);
					process.setStatus(ProcessStatus.QUEUED);
					process.setComment("Etl 1 file");
					process.save();
				}
			} else if (local.isDirectory()) {
				File[] files = local.listFiles();
				for (int i = 0; i < files.length; i++) {
					//kiem tra xem file co thay doi khong
					if (isCheckSumHasChange(local.getAbsolutePath(), configID)) {
						MyLog log = new MyLog();
						log.setConfig_id(configID);
						log.setStatus(LogStatus.EXTRACT_READY);
						log.setDownloadDT(new Timestamp(new Date().getTime()));
						log.setFilePath(files[i].getAbsolutePath());
						log.commitDownload();
					}

					// QUEUE a new process in db
					Process process = new Process();
					process.setDataConfigID(configID);
					process.setProcessConfigID(processConfigID);
					process.setStatus(ProcessStatus.QUEUED);
					process.setComment("Etl " + files.length + " files");
					process.save();
				}
			}
		}

	}
//	dowload file tu remote
	private static void downloadRemoteFiles(Statement statement, ResultSet rs) throws Exception {
		int configID = rs.getInt("id");
		String localDir = rs.getString("local_dir");
		String host = rs.getString("host");
		int port = rs.getInt("port");
		String userName = rs.getString("username");
		String password = rs.getString("password");
		String remoteDir = rs.getString("remote_dir");
		String types = rs.getString("file_extentions");
		String regex = rs.getString("file_regex");
		int processConfigID = rs.getInt("process_config_id");

		File folder = new File(localDir);

		if (!folder.exists())
			folder.mkdirs();
// command
		SSHManager instance = new SSHManager(userName, password, host, "", port);
		String errorMessage = instance.connect();
		//		kiem tra da ket noi duoc hay chua
		if (errorMessage != null) {
			System.out.println(errorMessage);
			connectFailed(statement, configID);
			return;
		}
		//gui command len server
		String listFilesCmd = "ls " + remoteDir;
		String[] allFiles = instance.sendCommand(listFilesCmd).split("\n");
//		files Need Download by extention and filename
		ArrayList<String> filesNeedDownload = filter(allFiles, types, regex); // filter by extention and
																				// filename
//		loc lai nhung file co thay doi
		filesNeedDownload = checkSum(instance, filesNeedDownload, remoteDir, configID);
		for (int i = 0; i < filesNeedDownload.size(); i++) {
			String rfile = filesNeedDownload.get(i);
			String lfile = localDir + "/" + rfile.substring(rfile.lastIndexOf("/") + 1);
			try {
				//	dowload file
				instance.download(lfile, rfile);
			} catch (Exception e) {
				MyLog log = new MyLog();
				log.setConfig_id(configID);
				log.setStatus(LogStatus.ERROR);
				log.setDownloadDT(new Timestamp(new Date().getTime()));
				log.setStatus(e.getMessage());
				log.setFilePath(lfile);
				log.commitDownload();
			}
			// Write log
			MyLog log = new MyLog();
			log.setConfig_id(configID);
			log.setStatus(LogStatus.EXTRACT_READY);
			log.setDownloadDT(new Timestamp(new Date().getTime()));
			log.setFilePath(lfile);
			log.commitDownload();
			System.out.println("Downloaded file: " + filesNeedDownload.get(i));
		}
		// QUEUE a new process in db
		Process process = new Process();
		process.setDataConfigID(configID);
		process.setStatus(ProcessStatus.QUEUED);
		process.setProcessConfigID(processConfigID);
		process.setComment("Etl " + filesNeedDownload.size() + " files");
		process.save();

		instance.close();
	}

	private static boolean isCheckSumHasChange(String src, int configID) throws IOException, SQLException {
		String md5 = getHashedMd5(src);
		return hasChange(md5 + "  " + src, configID); // 2 white space
	}

	private static String getHashedMd5(String src) throws IOException {
		try (InputStream is = Files.newInputStream(Paths.get(src))) {
			String md5 = org.apache.commons.codec.digest.DigestUtils.md5Hex(is);
			is.close();
			return md5;
		}
	}
//	loc ra nhung file có extentions can dowload ve
	private static ArrayList<String> filter(String[] allFiles, String types, String fileRegex) {
		ArrayList<String> result = new ArrayList<String>();
		// lay duoi file
		Pattern pattern = Pattern.compile(fileRegex);
		List<String> extentions = Arrays.asList(types.split(","));
		for (int i = 0; i < allFiles.length; i++) {
			String fileName = allFiles[i];
			if (extentions.contains(FileExtentionUtils.getExtention(fileName)) && pattern.matcher(fileName).matches()) {
				result.add(fileName);
			}
		}
		return result;
	}
// tra ve danh sach file co thay doi
	private static ArrayList<String> checkSum(SSHManager instance, ArrayList<String> filesNeedDownload,
			String remote_dir, int configID) throws SQLException {
		ArrayList<String> result = new ArrayList<String>();
		for (int i = 0; i < filesNeedDownload.size(); i++) {
			String absolutePath = remote_dir + "/" + filesNeedDownload.get(i);
			// gui command
			String checkSumCmd = "md5sum " + absolutePath;
			String respone = instance.sendCommand(checkSumCmd);
			// neu thay doi thi add vao
			if (hasChange(respone, configID)) {
				result.add(absolutePath);
			}
		}
		return result;
	}
//lay len checksum kiem ra voi file can dowload co thay doi khong
	private static boolean hasChange(String respone, int configID) throws SQLException {
		if (!respone.isEmpty()) {
			String[] spliter = respone.split("  "); // 2 white space
			Statement statement = DBConnector.loadControlConnection().createStatement();
			String sql = "SELECT * FROM `resources_control` WHERE remote_file = '" + spliter[1].replace("\\", "\\\\")
					+ "' AND " + "config_id = " + configID;
			ResultSet resultSet = statement.executeQuery(sql);
			if (resultSet.next()) {
				// neu nhu thay doi thi update lai resources_control
				if (!resultSet.getString("checksum").equals(spliter[0])) {
					sql = "UPDATE `resources_control` SET  resources_control.checksum = '" + spliter[0]
							+ "' WHERE  resources_control.id = " + resultSet.getInt("id");
					statement.executeUpdate(sql);
					return true;
				} else {
					return false;
				}
			} else {
				// neu nhu chua ton tai
				sql = "INSERT INTO `resources_control` (config_id,remote_file,checksum) VALUES(" + configID + ",'"
						+ spliter[1].replace("\\", "\\\\") + "','" + spliter[0] + "')";
				statement.executeUpdate(sql);
				return true;
			}
		}
		return false;
	}
//	set log neu connect loi
	private static void connectFailed(Statement statement, int configID) throws SQLException {
		String sql = "SELECT config.watcher FROM `config` WHERE config.id = " + configID;
		ResultSet rs = statement.executeQuery(sql);
		if (rs.next()) {
			String watcher = rs.getString("watcher");
			String subject = "DATAWAREHOUSE_2020 EROR NOTIFICATION";
			String mess = "The connection to the server failed!\r\nHave a look at dw_control.config[id=" + configID
					+ "]";
			EmailHelper maiHelper = new EmailHelper(watcher, subject, mess);
			// sent mail
			maiHelper.start();
		}

		MyLog log = new MyLog();
		log.setConfig_id(configID);
		log.setStatus(LogStatus.ERROR);
		log.setComment("Connect Failed");
		log.commitDownload();
	}


}
