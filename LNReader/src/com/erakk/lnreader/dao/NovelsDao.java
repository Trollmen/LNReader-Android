/**
 * 
 */
package com.erakk.lnreader.dao;

import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;

import org.jsoup.Connection.Response;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import android.content.Context;
import android.util.Log;

import com.erakk.lnreader.Constants;
import com.erakk.lnreader.helper.DBHelper;
import com.erakk.lnreader.helper.DownloadFileTask;
import com.erakk.lnreader.model.ImageModel;
import com.erakk.lnreader.model.NovelCollectionModel;
import com.erakk.lnreader.model.NovelContentModel;
import com.erakk.lnreader.model.PageModel;
import com.erakk.lnreader.parser.BakaTsukiParser;


/**
 * @author Nandaka
 * 
 */
public class NovelsDao {
	private static final String TAG = NovelsDao.class.toString();
	
	private ArrayList<PageModel> list;
	private static DBHelper dbh;
	
	public NovelsDao(Context context) {
		if(dbh == null)
			dbh = new DBHelper(context);	
	}
	
	public ArrayList<PageModel> getNovels(boolean forceRefresh) throws Exception{
		boolean refresh = false;
		PageModel page = dbh.getMainPage();
		
		// check if have main page data
		if(page == null) {
			refresh = true;
			Log.d(TAG, "No Main_Page data!");
		}
		else {
			Log.d(TAG, "Found Main_Page (" + page.getLastUpdate().toString() + "), last check: " + page.getLastCheck().toString());
			// compare if less than 7 day
			Date today = new Date();			
			if(today.getTime() - page.getLastCheck().getTime() > (7 * 3600 * 1000)) {				
				refresh = true;
				Log.d(TAG, "Last check is over 7 days, checking online status");
			}
		}
		
		if(refresh || forceRefresh){
			// get last updated page revision from internet
			PageModel mainPage = getPageModelFromInternet("Main_Page");
			mainPage.setType(PageModel.TYPE_OTHER);
			mainPage.setParent("");
			if(page!= null) mainPage.setId(page.getId());
			dbh.insertOrUpdatePageModel(mainPage);
			Log.d(TAG, "Updated Main_Page");
			
			// get updated novel list from internet
			list = getNovelsFromInternet();
			dbh.insertAllNovel(list);
			Log.d(TAG, "Updated Novel List");
		}
		else {
			list = dbh.selectAllByColumn(DBHelper.COLUMN_TYPE, PageModel.TYPE_NOVEL);
			Log.d(TAG, "Found: " + list.size());
		}
		return list;

	}
	
	public static PageModel getPageModel(String page) {
		return dbh.getPageModel(page);
	}
	
	public static PageModel getPageModelFromInternet(String page) throws Exception {
		Response response = Jsoup.connect("http://www.baka-tsuki.org/project/api.php?action=query&prop=info&format=xml&titles=" + page)
				 .timeout(60000)
				 .execute();
		return BakaTsukiParser.parsePageAPI(page, response.parse());
	}
	
	public ArrayList<PageModel> getNovelsFromInternet() throws Exception {
		list = new ArrayList<PageModel>();
		String url = Constants.BASE_URL + "/project";
		Response response = Jsoup.connect(url)
				 .timeout(60000)
				 .execute();
		Document doc = response.parse();//result.getResult();
		
		list = BakaTsukiParser.ParseNovelList(doc);
		
		Log.d(TAG, "Found: "+list.size()+" Novels");
		return list;
	}

	public NovelCollectionModel getNovelDetails(PageModel page) throws Exception {
		NovelCollectionModel novel = dbh.getNovelDetails(page.getPage());
		
		if(novel == null) {
			novel = getNovelDetailsFromInternet(page);
		}
		
		return novel;
	}
	
	public NovelCollectionModel getNovelDetailsFromInternet(PageModel page) throws Exception {
		Log.d(TAG, "Getting Novel Details from internet: " + page.getPage());
		NovelCollectionModel novel = null;
		
		Response response = Jsoup.connect(Constants.BASE_URL + "/project/index.php?title=" + page.getPage())
				 .timeout(60000)
				 .execute();
		Document doc = response.parse();
		
		novel = BakaTsukiParser.ParseNovelDetails(doc, page);
		PageModel novelPage = getPageModelFromInternet(page.getPage());
		novel.setLastUpdate(novelPage.getLastUpdate());
		
		dbh.insertNovelDetails(novel);
		
		// download cover image
		if(novel.getCoverUrl() != null) {
			DownloadFileTask task = new DownloadFileTask();
			ImageModel image = task.downloadImage(novel.getCoverUrl());
			Log.d("Image", image.toString());
		}
		Log.d(TAG, "Complete getting Novel Details from internet: " + page.getPage());
		return novel;
	}
	
	public static NovelContentModel getNovelContent(PageModel page) throws Exception {
		NovelContentModel content = null;
		
		// get from db
		content = dbh.getNovelContent(page.getPage());
		// get from Internet;
		if(content == null) {
			Log.d("getNovelContent", "Get from Internet: " + page.getPage());
			content = getNovelContentFromInternet(page);
		}
		
		return content;
	}

	public static NovelContentModel getNovelContentFromInternet(PageModel page) throws Exception {
		NovelContentModel content = new NovelContentModel();
		
		Response response = Jsoup.connect(Constants.BASE_URL + "/project/api.php?action=parse&format=xml&prop=text|images&page=" + page.getPage())
				 .timeout(60000)
				 .execute();
		Document doc = response.parse();
		
		content = BakaTsukiParser.ParseNovelContent(doc, page);
		content.setPageModel(page);
		
		// download all attached images
		DownloadFileTask task = new DownloadFileTask();
		for(Iterator<ImageModel> i = content.getImages().iterator(); i.hasNext();) {
			ImageModel image = i.next();
			image = task.downloadImage(image.getUrl());
		}
		
		// save to DB
		dbh.insertNovelContent(content);
		
		return content;
	}

	public PageModel updatePageModel(PageModel page) {
		return dbh.insertOrUpdatePageModel(page);		
	}
	
	public ArrayList<PageModel> getWatchedNovel() {
		return dbh.selectAllByColumn(DBHelper.COLUMN_IS_WATCHED, "1");
	}
	
	public static ImageModel getImageModel(String page) throws Exception {
		ImageModel image = null;
		
		image = dbh.getImage(page);
		if(image == null) {
			// try again using url
			image = dbh.getImageByReferer(page);
			if(image == null) {
				Log.d(TAG, "Image not found: " + page);
				image = getImageModelFromInternet(page);
				
				Log.d(TAG, "Referer: " + image.getReferer());
				dbh.insertImage(image);
			}
		}
		return image;
	}

	private static ImageModel getImageModelFromInternet(String page) throws Exception {
		Response response = Jsoup.connect(Constants.BASE_URL + page)
				 .timeout(60000)
				 .execute();
		Document doc = response.parse();
		ImageModel image = BakaTsukiParser.parseImagePage(doc); // only return the full image url
		
		DownloadFileTask downloader = new DownloadFileTask();
		image = downloader.downloadImage(image.getUrl());
		image.setReferer(page);
		
		return image;		
	}
}
