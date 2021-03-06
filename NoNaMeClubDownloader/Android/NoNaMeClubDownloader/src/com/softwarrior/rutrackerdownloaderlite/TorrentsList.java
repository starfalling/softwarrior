package com.softwarrior.rutrackerdownloaderlite;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ListActivity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.StatFs;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;

import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;

import com.google.ads.Ad;
import com.google.ads.AdListener;
import com.google.ads.AdRequest;
import com.google.ads.AdView;
import com.google.ads.AdRequest.ErrorCode;
import com.mobclix.android.sdk.MobclixAdView;
import com.mobclix.android.sdk.MobclixAdViewListener;
import com.mobclix.android.sdk.MobclixMMABannerXLAdView;
import com.softwarrior.rutrackerdownloaderlite.DownloadService.Controller.ControllerState;
import com.softwarrior.rutrackerdownloaderlite.DownloaderApp.ActivityResultType;
import com.softwarrior.widgets.TextProgressBar;
//----------------------------------------------------------------------------------
class TorrentContainer{
	public volatile ControllerState CtrlState = ControllerState.Undefined;
	public volatile String  Status = "";
	public volatile String  Name = "";
	public volatile String  SavePath = "";
	public volatile String  ContentName = "";
	public volatile int	Progress = 0;
	public volatile long TotalSize = 0;
	public volatile long ProgressSize = 0;
	public volatile int StorageMode = 2; //0-storage_mode_allocate, 1-storage_mode_sparse, 2-storage_mode_compact

	public TorrentContainer(String fileName, String contentName, int progress, long progressSize, long totalSize, int storageMode, String savePath){
    	Name=fileName; ContentName=contentName; Progress=progress;  ProgressSize=progressSize; TotalSize=totalSize; StorageMode=storageMode; SavePath = savePath; 
    }
}
//----------------------------------------------------------------------------------
public class TorrentsList extends ListActivity implements AdListener, MobclixAdViewListener {
    static public ArrayList<TorrentContainer> Torrents = new ArrayList<TorrentContainer>();
    
    private WakeLock mWakeLock;
    
    TorrentAdapter mAdapter;   
    
    static volatile private String mTorrentSavePathFull = DownloaderApp.DefaultTorrentSavePath;
	private Timer mAdRefreshTimer;
	private static final int mAdRefreshTime = 30000; //30 seconds
	private Handler mAdRefreshTimerHandler;
	//Mobclix
	private MobclixMMABannerXLAdView mAdviewBanner;
	//AdMob 
  	private AdView mAdView;
  	private AdRequest mAdRequest;    
 
	private Thread mStatusThread;
	private Runnable mStatusThreadRunnable; 
    private volatile boolean mStopProgress = false;
	private Timer mRefreshListTimer;
	private static final int mRefreshListTime = 500; //0.5 seconds
	private Handler mRefreshListTimerHandler;
 
	static TextView mRightText = null;
	static TextView mLeftText = null;
    
	private static final int DIALOG_REMOVE_TORRENTS = 33;
    
    enum MenuType{
    	About, Help, Main, FileManager, WebHistory, Exit;
    }

	public void OnClickHomeHandler(View v){
		DownloaderApp.MainScreen(this);
	}

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_CUSTOM_TITLE);
       
		PowerManager pm = (PowerManager)getSystemService(POWER_SERVICE);
		mWakeLock = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK
			| PowerManager.ACQUIRE_CAUSES_WAKEUP, "SHARED");
		mWakeLock.setReferenceCounted(false);

        // Use a custom layout file
        setContentView(R.layout.torrentslist);
        getWindow().setFeatureInt(Window.FEATURE_CUSTOM_TITLE, R.layout.custom_title);        
        
        Intent intent = getIntent();
        if(intent != null){
        	Uri localUri = getIntent().getData();
        	if(localUri != null)
        		DownloaderApp.TorrentFullFileName = localUri.getPath();
        }
        
        final ListView listView = getListView();
        // Tell the list view which view to display when the list is empty
        listView.setEmptyView(findViewById(R.id.empty));
        listView.setItemsCanFocus(false);
        listView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
      
        listView.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> parent, View view, int position, long id){
              TorrentContainer tc = Torrents.get(position);
              DownloaderApp.TorrentFullFileName = tc.Name;
              DownloaderApp.OpenTorrentDownloadActivity(TorrentsList.this);
            }
        });        
        // Set up our adapter
        mAdapter = new TorrentAdapter(this);
        setListAdapter(mAdapter);        

        mRightText = (TextView) findViewById(R.id.right_text);
        mLeftText = (TextView) findViewById(R.id.left_text);
        mLeftText.setTextColor(getResources().getColor(R.color.white));
        mLeftText.setTextAppearance(this, android.R.style.TextAppearance_Small);
        mLeftText.setTypeface(null,Typeface.BOLD);
        mRightText.setTextColor(getResources().getColor(R.color.white));
        mRightText.setTextAppearance(this, android.R.style.TextAppearance_Small);
        mRightText.setTypeface(null,Typeface.BOLD);
        

        // Start lengthy operation in a background thread
        mStatusThreadRunnable = new Runnable() {
            public void run() {
            	while (!mStopProgress) {
            		try {
						Thread.sleep(100);
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
					for(int i=0;i<Torrents.size();i++){
						TorrentContainer tc = Torrents.get(i);
						if(tc.CtrlState == ControllerState.Started){
							long progress_size = DownloadService.LibTorrents.GetTorrentProgressSize(tc.ContentName);
							if(progress_size>=0){ 
								tc.ProgressSize = progress_size;
							}else{
								tc.CtrlState = ControllerState.Undefined;
								continue;
							}
							int status = DownloadService.LibTorrents.GetTorrentState(tc.ContentName);
							if(status>=0){
								String txt_status = DownloadService.Controller.GetTorrentStateName(TorrentsList.this, status);
								tc.Status = txt_status;								
							}else{
								tc.CtrlState = ControllerState.Undefined;
								continue;
							}
							int progress = DownloadService.LibTorrents.GetTorrentProgress(tc.ContentName);
							if (progress>=0){ 
								tc.Progress = progress;
							}
						} else if(tc.CtrlState == ControllerState.Paused){
							int status = DownloadService.LibTorrents.GetTorrentState(tc.ContentName);
							if(status>=0){
								String txt_status = DownloadService.Controller.GetTorrentStateName(TorrentsList.this, status);
								tc.Status = txt_status;								
							}else{
								tc.CtrlState = ControllerState.Undefined;
								continue;
							}
						}
						else{
							tc.Status = getString(R.string.text_torrent_state_undefined);
						}							
                		try {
							Thread.sleep(1);
						} catch (InterruptedException e) {
							e.printStackTrace();
						}
					}
				}
            }
        };
        mStatusThread = new Thread(mStatusThreadRunnable);
        mStatusThread.start();                                        
        mRefreshListTimer = new Timer();
        mRefreshListTimer.schedule(new RefreshListTimerTask(), mRefreshListTime, mRefreshListTime);
        mRefreshListTimerHandler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                mAdapter.notifyDataSetChanged();
                long avalMb = GetAvailibleMB(mTorrentSavePathFull);
                String text_r = "" + avalMb + " MB";
                String text_l = getString(R.string.available_space) + ":";
                mLeftText.setText(text_l);
                mRightText.setText(text_r);
                if(avalMb == 0){
                	mLeftText.setTextColor(Color.RED);
                	mRightText.setTextColor(Color.RED);
                }else{
                	mLeftText.setTextColor(getResources().getColor(R.color.white));
                	mRightText.setTextColor(getResources().getColor(R.color.white));                	
                }                
            }
        };        

        //Mobclix
        mAdviewBanner = (MobclixMMABannerXLAdView) findViewById(R.id.advertising_banner_view);	        
        //AdMob
        mAdView = (AdView) findViewById(R.id.adView);
        
        Context context = getApplicationContext();
        if(DownloaderApp.CheckMode(context) && DownloaderApp.CheckService(context)){
        	mAdviewBanner.setVisibility(View.GONE);
        	mAdView.setVisibility(View.GONE);
        } else{
	        //Mobclix
	        mAdviewBanner.addMobclixAdViewListener(this);
    		mAdviewBanner.getAd();
	        //AdMob
    		mAdView.setAdListener(this);
	        mAdRequest = new AdRequest();
	        //mAdRequest.setTesting(true);
	        mAdView.loadAd(mAdRequest);

	        mAdRefreshTimer = new Timer();
	        mAdRefreshTimer.schedule(new AdRefreshTimerTask(), mAdRefreshTime, mAdRefreshTime);
	        mAdRefreshTimerHandler = new Handler() {
	            @Override
	            public void handleMessage(Message msg) {
					mAdView.loadAd(mAdRequest);
	        		mAdviewBanner.getAd();
	            }
	        };        
        }
        
        if(DownloaderApp.ExitState) DownloaderApp.CloseApplication(this);
        DownloaderApp.AnalyticsTracker.trackPageView("/TorrentsList");
    }
    
    @Override
    protected void onDestroy() {
		mStopProgress = true;
		if(mWakeLock.isHeld()) {
		    Log.w(DownloaderApp.TAG, "WakeLock is still held");
		    mWakeLock.release();
		}
		super.onDestroy();
    }
  
    @Override
    protected void onResume() {
        super.onResume();
        if(DownloaderApp.TorrentFullFileName.equals("undefined")){        	
        }
        else{
            boolean open_result=false;
        	String contentName = DownloadService.LibTorrents.GetTorrentName(DownloaderApp.TorrentFullFileName);
			if(contentName != null && contentName.length() > 0){
				String savePath = DownloadPreferencesScreen.GetTorrentSavePath(this);
				AddTorrent(this, DownloaderApp.TorrentFullFileName, 0, 0, -1, savePath);
				open_result = true;
			}
			if(open_result==false){
				DownloaderApp.TorrentFullFileName = new String("undefined");
				Toast.makeText(this, getString(R.string.open_torrent_file_error), Toast.LENGTH_LONG).show();
			}
        }
        mTorrentSavePathFull = DownloadPreferencesScreen.GetTorrentSavePath(this);
        mWakeLock.acquire();
        mAdapter.notifyDataSetChanged();
        if(DownloaderApp.ExitState) DownloaderApp.CloseApplication(this);
    }
    
    @Override
    protected void onPause() {
    	mWakeLock.release();
    	super.onPause();
    	if(mAdRefreshTimer != null){
    		mAdRefreshTimer.cancel(); 
    		mAdRefreshTimer = null;
    	}
    	if(mRefreshListTimer != null){
    		mRefreshListTimer.cancel(); 
    		mRefreshListTimer = null;
    	}
    	if(mStatusThread != null){
    		mStopProgress = true;
    		mStatusThread = null;
    	}
    }
    
    @Override
    protected void onRestart() {
    	super.onRestart();
    	mAdRefreshTimer = new Timer();
    	mAdRefreshTimer.schedule(new AdRefreshTimerTask(), 0, mAdRefreshTime);
    	mRefreshListTimer = new Timer();
    	mRefreshListTimer.schedule(new RefreshListTimerTask(), 0, mRefreshListTime);
		mStopProgress = false;
    	mStatusThread = new Thread(mStatusThreadRunnable);
        mStatusThread.start();                                        
    }	    		
    
    private class AdRefreshTimerTask extends TimerTask {			
		@Override
		public void run() {
			if(mAdRefreshTimerHandler!=null)
			    mAdRefreshTimerHandler.sendEmptyMessage(0);
		}	    	
    }	   
    private class RefreshListTimerTask extends TimerTask {			
		@Override
		public void run() {
			if(mRefreshListTimerHandler!=null)
			    mRefreshListTimerHandler.sendEmptyMessage(0);
		}	    	
    }	   
    public static long GetAvailibleMB(String path){
    	long megAvailable = 0;
    	try{    		
	    	if(path != null && path.length() >3 ){
	    		StatFs stat = new StatFs(path); 
		    	long bytesAvailable = (long)stat.getBlockSize() * (long)stat.getAvailableBlocks();
		    	megAvailable = bytesAvailable / 1048576;
		        //SdSize  totalBlocks * blockSize
		        //SdAvail availableBlocks * blockSize
	    	}
    	} catch(Exception ex){}
    	return megAvailable;    
    }
    
    static public void AddTorrent(Context context, String FileName, int progress, long progressSize, int storageMode, String savePath){
        if(FileName.equals("undefined"))
        	return;
    	for(int i=0;i<Torrents.size();i++){
    		TorrentContainer tc = Torrents.get(i);
    		if(tc.Name.equals(FileName))
    			return;
    	}
		try {
	    	File file = new File(FileName);
			FileInputStream input = new FileInputStream(file);
			input.close();
		} catch (Exception e){
			return;
		}
		String contentName = DownloadService.LibTorrents.GetTorrentName(FileName);
		boolean add_file = false;
		if(contentName != null && contentName.length() > 0){
			long totalSize = DownloadService.LibTorrents.GetTorrentSize(FileName);
			if(storageMode == -1){
				storageMode = 0;
				if(totalSize > DownloaderApp.StorageModeCompactMB)
					storageMode = 2; //0-storage_mode_allocate, 1-storage_mode_sparse, 2-storage_mode_compact
			}				
			if(totalSize > 0){
				Torrents.add(new TorrentContainer(FileName, contentName, progress, progressSize, totalSize, storageMode, savePath));
				add_file = true;
			}
		}
		if(add_file == false){
			Toast.makeText(context, context.getString(R.string.open_torrent_file_error), Toast.LENGTH_LONG).show();
		} else{
			DownloaderApp.StoreTorrentsToDB(context);
		}		
    }

    static public void FinalRemoveTorrents(){
    	while(Torrents.size()>0){
    		TorrentContainer tc = Torrents.get(0);
    		DownloadService.LibTorrents.RemoveTorrent(tc.ContentName);
    		try {
				Thread.sleep(10);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
    		Torrents.remove(0);
    	}
    }

    static private void RemoveTorrent(Context context, String FileName){
    	for(int i=0;i<Torrents.size();i++){
    		TorrentContainer tc = Torrents.get(i);
    		if(tc.Name.equals(FileName)){
   				DownloadService.LibTorrents.RemoveTorrent(tc.ContentName);
   	    		try {
   					Thread.sleep(10);
   				} catch (InterruptedException e) {
   					e.printStackTrace();
   				}
    			Torrents.remove(i);
    			DownloaderApp.StoreTorrentsToDB(context);
    		    DownloaderApp.TorrentFullFileName =  new String("undefined");
    			return;
    		}
    	}
    }
    static public String GetSavePath(String FileName){
    	if(FileName.equals("undefined"))
        	return "";
    	for(int i=0;i<Torrents.size();i++){
    		TorrentContainer tc = Torrents.get(i);
    		if(tc.Name.equals(FileName)){
    			return tc.SavePath;
    		}
    	}
    	return "";
    }
    static public ControllerState GetCtrlState(String FileName){
        if(FileName.equals("undefined"))
        	return ControllerState.Undefined;
    	for(int i=0;i<Torrents.size();i++){
    		TorrentContainer tc = Torrents.get(i);
    		if(tc.Name.equals(FileName)){
    			return tc.CtrlState;
    		}
    	}
    	return ControllerState.Undefined;
    }
    static public void SetCtrlState(String FileName, ControllerState ctrlState){
        if(FileName.equals("undefined"))
        	return;
    	for(int i=0;i<Torrents.size();i++){
    		TorrentContainer tc = Torrents.get(i);
    		if(tc.Name.equals(FileName)){
    			tc.CtrlState = ctrlState;
    			return;
    		}
    	}
    }
    static public int GetStorageMode(String FileName){
        if(FileName.equals("undefined"))
        	return -1;
    	for(int i=0;i<Torrents.size();i++){
    		TorrentContainer tc = Torrents.get(i);
    		if(tc.Name.equals(FileName)){
    			return tc.StorageMode;
    		}
    	}
    	return -1;
    }
    static public void SetStorageMode(String FileName, int storageMode){
        if(FileName.equals("undefined"))
        	return;
    	for(int i=0;i<Torrents.size();i++){
    		TorrentContainer tc = Torrents.get(i);
    		if(tc.Name.equals(FileName)){
    			tc.StorageMode = storageMode;
    			return;
    		}
    	}
    }    
    static public long GetProgressSize(String FileName){
        if(FileName.equals("undefined"))
        	return 0;
    	for(int i=0;i<Torrents.size();i++){
    		TorrentContainer tc = Torrents.get(i);
    		if(tc.Name.equals(FileName)){
    			return tc.ProgressSize;
    		}
    	}
    	return 0;
    }
    static public int GetProgress(String FileName){
        if(FileName.equals("undefined"))
        	return 0;
    	for(int i=0;i<Torrents.size();i++){
    		TorrentContainer tc = Torrents.get(i);
    		if(tc.Name.equals(FileName)){
    			return tc.Progress;
    		}
    	}
    	return 0;
    }
    @Override
    protected Dialog onCreateDialog(int id) {
        switch (id) {
        case DIALOG_REMOVE_TORRENTS:
            return new AlertDialog.Builder(TorrentsList.this)
                .setTitle(R.string.remove_torrents_dialog_title)
                .setPositiveButton(R.string.dialog_ok, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {
                    	while(Torrents.size()>0){
                    		TorrentContainer tc = Torrents.get(0);
                    		if(tc.CtrlState == ControllerState.Started){
                            	DownloadService.LibTorrents.RemoveTorrent(tc.ContentName);
                            	tc.CtrlState = ControllerState.Stopped;
                    		}
                    		Torrents.remove(0);
                    	}    	
                        mAdapter.notifyDataSetChanged();
                        DownloaderApp.TorrentFullFileName = new String("undefined");
                    }
                }).setNegativeButton(R.string.dialog_cancel, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {
                        //User clicked Cancel so do some stuff
                    }
                }).create();
        }
        return null;
    }
    public void OnClickButtonAddTorrent(View v){
        DownloaderApp.FileManagerActivity(this);
    }
    public void OnClickButtonRemoveTorrents(View v){
    	if(Torrents.size() > 0)
    		showDialog(DIALOG_REMOVE_TORRENTS);
    }    
    public void OnClickButtonResumeAll(View v){
    	for(int i=0;i<Torrents.size();i++){
    		TorrentContainer tc = Torrents.get(i);
    		if(tc.CtrlState == ControllerState.Paused || tc.CtrlState == ControllerState.Started){
	        	DownloadService.LibTorrents.ResumeTorrent(tc.ContentName);
	        	tc.CtrlState = ControllerState.Started;
    		}
    	}    	
    }
    public void OnClickButtonPauseAll(View v){
    	for(int i=0;i<Torrents.size();i++){
    		TorrentContainer tc = Torrents.get(i);
    		if(tc.CtrlState == ControllerState.Started){
	        	DownloadService.LibTorrents.PauseTorrent(tc.ContentName);
	        	tc.CtrlState = ControllerState.Paused;
    		}
    	}    	    	
    }
    public void OnClickButtonStopAll(View v){
    	for(int i=0;i<Torrents.size();i++){
    		TorrentContainer tc = Torrents.get(i);
        	DownloadService.LibTorrents.RemoveTorrent(tc.ContentName);
        	tc.CtrlState = ControllerState.Stopped;
    	}
    }
    public void OnClickButtonStartAll(View v){
    	for(int i=0;i<Torrents.size();i++){
    		TorrentContainer tc = Torrents.get(i);
    		try{
    			FileInputStream fis = new FileInputStream(tc.Name); 
        		fis.close();  		
        	}
        	catch(Exception ex){
                Toast.makeText(this, R.string.error_torrent_file_absent, Toast.LENGTH_SHORT).show();
                return;
        	}
    		if(tc.SavePath.length() < 3) tc.SavePath = DownloadPreferencesScreen.GetTorrentSavePath(this); 
        	String tempName = DownloadService.Controller.CopyTorrentFiles("downloader_temp.torrent", tc.Name);
        	//0-storage_mode_allocate
        	//1-storage_mode_sparse
        	//2-storage_mode_compact
        	int storageMode = tc.StorageMode;
        	//if(tc.StorageMode == 0) storageMode = 1;
        	boolean res = DownloadService.LibTorrents.AddTorrent(tc.SavePath, tempName, storageMode);
        	if(res == true) tc.CtrlState = ControllerState.Started;
        	if(!tempName.equals(DownloaderApp.TorrentFullFileName))
        		DownloadService.Controller.DeleteFile(tempName);
    	}    	
    }
            
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch(ActivityResultType.getValue(resultCode))
        {
        case RESULT_DOWNLOADER:{
        	break;
        }        	
        case RESULT_MAIN:
        case RESULT_EXIT:
            setResult(resultCode);
            finish();
            break;
        }      
    }
    
    public class TorrentAdapter extends BaseAdapter {
        private LayoutInflater mInflater;
        private Context mContext;

        public TorrentAdapter(Context c) { 
            mContext = c;
            mInflater = (LayoutInflater)mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        }
        public int getCount(){ return Torrents.size(); }
        public Object getItem(int position) { return position;}
        public long getItemId(int position) { return position;}
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = null;
            if( convertView != null )
                v = convertView;
            else
                v = mInflater.inflate(R.layout.torrent_row, parent, false);
            TorrentContainer tc = Torrents.get(position);
            TextView tv_status = (TextView)v.findViewById( R.id.status );
            TextView tv_name = (TextView)v.findViewById( R.id.childname );
            TextProgressBar pb_progress = (TextProgressBar)v.findViewById(R.id.progress_horizontal);
            Button b_close = (Button)v.findViewById(R.id.button_menu);
            if(b_close != null){
	            b_close.setTag(tc);
	            b_close.setOnClickListener(new OnClickListener() {			
					public void onClick(View v) {
						TorrentContainer tc = (TorrentContainer)v.getTag();
						RemoveTorrent(TorrentsList.this,tc.Name);
					}
	    		});
            }
            if(tv_name != null){
            	tv_name.setText(tc.ContentName);
            }
            if(tv_status != null){
            	String status = tc.Status;
            	if(status.length() < 1)
            		tv_status.setText(R.string.text_torrent_state_undefined);
            	else
            		tv_status.setText(status);          		
            }
			if(pb_progress!=null){
				pb_progress.setProgress(tc.Progress);
				pb_progress.setText(Long.toString(tc.ProgressSize)+ "/" + Long.toString(tc.TotalSize)+ "MB");
			}
            return v;
        }
    }
    
	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		super.onCreateOptionsMenu(menu);
		menu.add(Menu.NONE, MenuType.About.ordinal(), MenuType.About.ordinal(), R.string.menu_about); 
		menu.add(Menu.NONE, MenuType.Help.ordinal(), MenuType.Help.ordinal(), R.string.menu_help); 
		menu.add(Menu.NONE, MenuType.Main.ordinal(), MenuType.Main.ordinal(), R.string.menu_main);
		menu.add(Menu.NONE, MenuType.FileManager.ordinal(), MenuType.FileManager.ordinal(), R.string.menu_file_manager);
		menu.add(Menu.NONE, MenuType.WebHistory.ordinal(), MenuType.WebHistory.ordinal(), R.string.menu_web_history);
		menu.add(Menu.NONE, MenuType.Exit.ordinal(), MenuType.Exit.ordinal(), R.string.menu_exit);
	    DownloaderApp.SetMenuBackground(this);
		return true;
	}	
	@Override
	public boolean onMenuItemSelected(int featureId, MenuItem item) {
		super.onMenuItemSelected(featureId, item);
		MenuType type = MenuType.values()[item.getItemId()];
		switch(type)
		{
		case About:{
			DownloaderApp.AboutActivity(this);
		} break;
		case Help:{
			DownloaderApp.HelpActivity(this);
		} break;
		case Main:{
			DownloaderApp.MainScreen(this);
		} break;
		case FileManager:{
			DownloaderApp.FileManagerActivity(this);
		} break;
		case WebHistory:{
			DownloaderApp.WebHistoryActivity(this);
		} break;
		case Exit:{
			if(DownloaderApp.DownloadServiceMode)
				DownloaderApp.FinalCloseApplication(this);
			else
				DownloaderApp.CloseApplication(this);
		} break;
		}
		return true;
	}
	//Mobclix
	public String keywords()	{ return null;}
	public String query()		{ return null;}
	public void onAdClick(MobclixAdView arg0) {
		Log.v(DownloaderApp.TAG, "Mobclix clicked");
	}
	public void onCustomAdTouchThrough(MobclixAdView adView, String string) {
		Log.v(DownloaderApp.TAG, "Mobclix The custom ad responded with '" + string + "' when touched!");
	}
	public boolean onOpenAllocationLoad(MobclixAdView adView, int openAllocationCode) {
		Log.v(DownloaderApp.TAG, "Mobclix The ad request returned open allocation code: " + openAllocationCode);
		return false;
	}
	public void onSuccessfulLoad(MobclixAdView view) {
		Log.v(DownloaderApp.TAG, "Mobclix The ad request was successful!");
		view.setVisibility(View.VISIBLE);
	}
	public void onFailedLoad(MobclixAdView view, int errorCode) {
		Log.v(DownloaderApp.TAG, "Mobclix The ad request failed with error code: " + errorCode);
		view.setVisibility(View.GONE);
	}
	//AdMob
	public void onDismissScreen(Ad ad) {
		Log.v(DownloaderApp.TAG, "AdMob onDismissScreen");
	}
	public void onFailedToReceiveAd(Ad ad, ErrorCode errorCode) {
		Log.v(DownloaderApp.TAG, "AdMob failed to receive ad (" + errorCode + ")");			
	}
	public void onLeaveApplication(Ad ad) {
		Log.v(DownloaderApp.TAG, "AdMob onLeaveApplication");
	}
	public void onPresentScreen(Ad ad) {
		Log.v(DownloaderApp.TAG, "AdMob onLeaveApplication");			
	}
	public void onReceiveAd(Ad ad) {
		Log.v(DownloaderApp.TAG, "AdMob onReceiveAd");						
	}				
}
