package com.softwarrior.rutrackerdownloaderlite;

import java.io.File;
import java.io.FileInputStream;
import java.net.URI;
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

import com.softwarrior.libtorrent.LibTorrent;
import com.softwarrior.rutrackerdownloaderlite.DownloaderApp.ActivityResultType;
import com.softwarrior.widgets.TextProgressBar;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

public class DownloadService extends Service {
    
	public static final LibTorrent LibTorrents = new LibTorrent();
	
	private NotificationManager mNM;
    private int mStartId = 0;
    
    private final IBinder mBinder = new LocalBinder();
    public class LocalBinder extends Binder {
    	DownloadService getService() {
            return DownloadService.this;
        }
    }                
    void StopServiceSelf()
    {
		hideNotification();            
		Log.d(DownloaderApp.TAG, "StopServiceSelf");            
		stopSelf(mStartId);
    }
         
    @Override
    public void onCreate() {
        mNM = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
//      Toast.makeText(this, R.string.service_created,Toast.LENGTH_SHORT).show();        
//      mInvokeIntent = new Intent(this, Controller.class); //This is who should be launched if the user selects our persistent notification.
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
		mStartId = startId;
		String txt = null;
		if ((flags & Service.START_FLAG_REDELIVERY) == 0) {
		    txt = "New startId=" + mStartId;
		} else {
		    txt = "Re-delivered startId=" + mStartId;
		}
		Log.d(DownloaderApp.TAG, "Service Starting: " + txt);
	    showNotification(getString(R.string.service_created)); 
		
		int listenPort = DownloadPreferencesScreen.GetListenPort(getApplicationContext());
		int uploadLimit = DownloadPreferencesScreen.GetUploadLimit(getApplicationContext());
		int downloadLimit =  DownloadPreferencesScreen.GetDownloadLimit(getApplicationContext());
		boolean upnp = DownloadPreferencesScreen.GetUPNP(getApplicationContext());
		boolean lsd = DownloadPreferencesScreen.GetLSD(getApplicationContext());
		boolean natpmp = DownloadPreferencesScreen.GetNATPMP(getApplicationContext());
		
		LibTorrents.SetSession(listenPort, uploadLimit, downloadLimit);
		//-----------------------------------------------------------------------------
	    //enum proxy_type
	    //{
		//0 - none, // a plain tcp socket is used, and the other settings are ignored.
		//1 - socks4, // socks4 server, requires username.
		//2 - socks5, // the hostname and port settings are used to connect to the proxy. No username or password is sent.
		//3 - socks5_pw, // the hostname and port are used to connect to the proxy. the username and password are used to authenticate with the proxy server.
		//4 - http, // the http proxy is only available for tracker and web seed traffic assumes anonymous access to proxy
		//5 - http_pw // http proxy with basic authentication uses username and password
	    //};
	    //-----------------------------------------------------------------------------
		int type = DownloadPreferencesScreen.GetProxyType(getApplicationContext());
		String hostName = DownloadPreferencesScreen.GetHostName(getApplicationContext());
		int port = DownloadPreferencesScreen.GetPortNumber(getApplicationContext());
		String userName = DownloadPreferencesScreen.GetUserName(getApplicationContext());
		String password = DownloadPreferencesScreen.GetUserPassword(getApplicationContext());

		LibTorrents.SetProxy(type, hostName, port, userName, password);   
		
		LibTorrents.SetSessionOptions(lsd,upnp,natpmp);
    					
        return START_REDELIVER_INTENT; //START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    //Show a notification while this service is running.
    public void showNotification(String text) {
        // Set the icon, scrolling text and timestamp
        Notification notification = new Notification(R.drawable.ic_stat_notify, text, System.currentTimeMillis());
        // The PendingIntent to launch our activity if the user selects this notification
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, new Intent(this, TorrentsList.class), 0);
        // Set the info for the views that show in the notification panel.
        notification.setLatestEventInfo(this, getText(R.string.service_label),text, contentIntent);
        // We show this for as long as our service is processing a command.
        notification.flags |= Notification.FLAG_ONGOING_EVENT;        
        // Send the notification.
        // We use a string id because it is a unique number.  We use it later to cancel.
        mNM.notify(R.string.service_created, notification);
    }
    
    private void hideNotification() {
        mNM.cancel(R.string.service_created);
    }    
    // ----------------------------------------------------------------------
    public static class Controller extends FullWakeActivity implements AdListener, MobclixAdViewListener {
        
    	private volatile boolean mIsBound = false;
        private volatile boolean mIsBoundService = false; 
        private DownloadService mBoundService = null;

        private volatile boolean mStopProgress = false;
        private volatile int mTorrentProgress = 0;
        private volatile long mTorrentProgressSize = 0;
        private volatile long mTorrentTotalSize = 0;
        private volatile int mTorrentState = 0;
        private volatile String mTorrentStatus = new String();
        private volatile String mSessionStatus = new String();
        private volatile String mTorrentContentName = new String();
        private volatile String mTorrentSavePath  = new String();
        
        private TextProgressBar mProgress;

        private Handler mHandler = new Handler();        
        
//        private Button mButtonStart;
//        private Button mButtonStop;
//        private Button mButtonPause;
//        private Button mButtonResume;
        private Button mButtonSelectFiles;
        private TextView mTextViewTorrentState; 
        private TextView mTextViewCommonStatus;

        private CheckBox mCheckBoxStorageMode;
        
    	StopHandler mStopHandler = null;
    	Message mStopMessage = null;

		private Timer mAdRefreshTimer;
		private static final int mAdRefreshTime = 30000; //30 seconds
		private Handler mAdRefreshTimerHandler;
		private Timer mAdInitTimer;
		private static final int mAdInitTime = 500; //0.5 seconds
		private Handler mAdInitTimerHandler;
		private Thread mStatusThread;
		private Runnable mStatusThreadRunnable;

		private static final int SELECT_FILE_ACTIVITY = 222;		

		//StorageMode:
		//0-storage_mode_allocate
		//1-storage_mode_sparse
		//2-storage_mode_compact
		public static int StorageMode  = 2;
		
        //Mobclix
		private MobclixMMABannerXLAdView mAdviewBanner;
		//AdMob 
	  	private AdView mAdView;
	  	private AdRequest mAdRequest;
	  	
        public enum ControllerState{
        	Undefined, Started, Stopped, Paused
        }          
        enum TorrentState {
        	queued_for_checking, checking_files, downloading_metadata, downloading, finished, seeding, allocating, checking_resume_data, paused, queued
        }
        enum MenuType{
        	About, Help, Main, FileManager, WebHistory, Exit;
        }
        
        private volatile ControllerState mControllerState = ControllerState.Undefined;
        
    	@Override
		public void onCreate(Bundle savedInstanceState) {
    		super.onCreate(savedInstanceState);
            setContentView(R.layout.service);
            setTitleColor(getResources().getColor(R.color.gold));
            Intent intent = getIntent();
            if(intent != null){
            	Uri localUri = getIntent().getData();
            	if(localUri != null)
            		DownloaderApp.TorrentFullFileName = localUri.getPath();
            }
            //Mobclix
	        mAdviewBanner = (MobclixMMABannerXLAdView) findViewById(R.id.advertising_banner_view);
            //AdMob
	        mAdView = (AdView) findViewById(R.id.adView);

	        if(DownloaderApp.DownloadServiceMode){
	        	DownloaderApp.StartServiceActivity(this);
	        	DownloaderApp.RestoryWebHistoryFromDB(this);
	        	DownloaderApp.RestoryTorrentsFromDB(this);
	        }
	        
	        mAdInitTimer = new Timer();
	        mAdInitTimer.schedule(new AdInitTimerTask(), mAdInitTime);
	        mAdInitTimerHandler = new Handler() {
	            @Override
	            public void handleMessage(Message msg) {
	    	        if(DownloaderApp.CheckMode(Controller.this) && DownloaderApp.CheckService(Controller.this)){
	    	        	mAdviewBanner.setVisibility(View.GONE);
	    	        	mAdView.setVisibility(View.GONE);
//	    	        	RutrackerDownloaderApp.ActivateTorrentFileList = true;
	    	        } else{
	    	            //Mobclix
	    	        	mAdviewBanner.addMobclixAdViewListener(Controller.this);
	    	    		mAdviewBanner.getAd();
	    	    		//AdMob
	    	    		mAdView.setAdListener(Controller.this);
	    		        mAdRequest = new AdRequest();
//	    		        mAdRequest.setTesting(true);
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
	            }
	        };        	        	
	        	        
            mProgress = (TextProgressBar) findViewById(R.id.progress_horizontal);

//            mButtonStart = (Button)findViewById(R.id.ButtonStartDownload);
//            mButtonStop = (Button)findViewById(R.id.ButtonStopDownload);
//            mButtonPause = (Button)findViewById(R.id.ButtonPauseDownload);
//            mButtonResume = (Button)findViewById(R.id.ButtonResumeDownload); 
            mButtonSelectFiles = (Button)findViewById(R.id.ButtonSelectFiles);
            mTextViewTorrentState = (TextView)findViewById(R.id.TextViewTorrentState);
            mTextViewCommonStatus = (TextView)findViewById(R.id.TextViewCommonStatus);
            
            mCheckBoxStorageMode = (CheckBox)findViewById(R.id.ButtonStorageMode);
            
            // Start lengthy operation in a background thread
            mStatusThreadRunnable = new Runnable() {
                public void run() {
                	while (!mStopProgress) {
                		try {
							Thread.sleep(100);
						} catch (InterruptedException e) {
							e.printStackTrace();
						}
						if((mIsBoundService && mControllerState == ControllerState.Started) ||
						   (mIsBoundService && mControllerState == ControllerState.Paused)) {
								long progress_size = LibTorrents.GetTorrentProgressSize(mTorrentContentName);
								if(progress_size>=0){
									mTorrentProgressSize = progress_size; 
								} else{
									continue;
								}
								int state = LibTorrents.GetTorrentState(mTorrentContentName);
								if(state>=0){
									mTorrentState = state;
								} else{
									continue;
								}
								String t_status = LibTorrents.GetTorrentStatusText(mTorrentContentName);
								if(t_status!=null){
									mTorrentStatus = t_status;
								} else{
									continue;									
								}
								String s_status = LibTorrents.GetSessionStatusText();
								if(s_status!=null){
									mSessionStatus = s_status; 
								} else{
									continue;									
								}
								int progress = LibTorrents.GetTorrentProgress(mTorrentContentName);
								if(progress>=0){
									mTorrentProgress = progress; 
								} 
								mHandler.post(new Runnable() {
									public void run() {
										mProgress.setProgress(mTorrentProgress);
										mProgress.setText(Long.toString(mTorrentProgressSize)+ "/" + Long.toString(mTorrentTotalSize)+ "MB");
										SetTorrentState();
										SetCommonStatus();
									}
							});
						}
					}
                }
            };
            mStatusThread = new Thread(mStatusThreadRunnable);
            mStatusThread.start();
            RestoreControllerState();
		    doBindService();
    		if(DownloaderApp.ExitState){
    			if(DownloaderApp.DownloadServiceMode)
    				DownloaderApp.FinalCloseApplication(this);
    			else
    				DownloaderApp.CloseApplication(this);    			
    		}
		    DownloaderApp.AnalyticsTracker.trackPageView("/DownloadServiceControler");
        }
	    private class AdInitTimerTask extends TimerTask {			
			@Override
			public void run() {
				if(mAdInitTimerHandler!=null)
					mAdInitTimerHandler.sendEmptyMessage(0);
			}	    	
	    }	    
    	private class AdRefreshTimerTask extends TimerTask {			
			@Override
			public void run() {
				if(mAdRefreshTimerHandler!=null)
					mAdRefreshTimerHandler.sendEmptyMessage(0);
			}	    	
	    }	    
    	void RestoreControllerState(){
    		mControllerState = TorrentsList.GetCtrlState(DownloaderApp.TorrentFullFileName);
    		mTorrentSavePath = TorrentsList.GetSavePath(DownloaderApp.TorrentFullFileName);
    		if(mTorrentSavePath.length() < 3) mTorrentSavePath = DownloadPreferencesScreen.GetTorrentSavePath(this);
			mTorrentTotalSize = LibTorrents.GetTorrentSize(DownloaderApp.TorrentFullFileName);
			if(mTorrentTotalSize < 0 ) mTorrentTotalSize = 0;
			StorageMode = TorrentsList.GetStorageMode(DownloaderApp.TorrentFullFileName);			
			if(StorageMode < 0 ){
				StorageMode = 0;
				if(mTorrentTotalSize > DownloaderApp.StorageModeCompactMB) StorageMode = 2;
			}
			if(StorageMode > 0)
				mCheckBoxStorageMode.setChecked(false);
			else
				mCheckBoxStorageMode.setChecked(true);
            mTorrentProgressSize = TorrentsList.GetProgressSize(DownloaderApp.TorrentFullFileName);
            mTorrentProgress = TorrentsList.GetProgress(DownloaderApp.TorrentFullFileName);			
			if(mTorrentTotalSize < 0 ) mTorrentTotalSize = 0;
			mProgress.setProgress(mTorrentProgress);			
			mProgress.setText(Long.toString(mTorrentProgressSize)+ "/" + Long.toString(mTorrentTotalSize)+ "MB");
            SetControllerState(mControllerState);
    	}
    	void SaveControllerState(){
    		TorrentsList.AddTorrent(this, DownloaderApp.TorrentFullFileName, mTorrentProgress, mTorrentProgressSize, StorageMode, mTorrentSavePath);
    		TorrentsList.SetCtrlState(DownloaderApp.TorrentFullFileName, mControllerState);
    		TorrentsList.SetStorageMode(DownloaderApp.TorrentFullFileName, StorageMode);
    	}
    	void SetControllerState(ControllerState controllerState){
    		mControllerState = controllerState; 
            switch (mControllerState) {
			case Started:{
//			       mButtonStart.setEnabled(false);
//			       mButtonStop.setEnabled(true);
//			       mButtonPause.setEnabled(true);
//			       mButtonResume.setEnabled(false);
				   mCheckBoxStorageMode.setEnabled(false);
			       mButtonSelectFiles.setEnabled(true);
//			       if(mIsBoundService)
//			    	   mBoundService.showNotification(getString(R.string.text_download_started));
			} break;
			case Paused:{
//			       mButtonStart.setEnabled(false);
//			       mButtonStop.setEnabled(true);
//			       mButtonPause.setEnabled(false);
//			       mButtonResume.setEnabled(true);
				   mCheckBoxStorageMode.setEnabled(false);
//			       if(mIsBoundService)
//			    	   mBoundService.showNotification(getString(R.string.text_download_paused));
			} break;
			case Stopped:
//					if(mIsBoundService)
//						mBoundService.showNotification(getString(R.string.text_download_stopped));
			case Undefined:
			default: {
//				   mButtonStart.setEnabled(true);
//			       mButtonStop.setEnabled(false);
//			       mButtonPause.setEnabled(false);
//			       mButtonResume.setEnabled(false);
				   mCheckBoxStorageMode.setEnabled(true);
			       mButtonSelectFiles.setEnabled(false);
			       mTextViewTorrentState.setText(R.string.text_torrent_state_undefined);
			       mTextViewCommonStatus.setText(R.string.text_common_status);
			       mProgress.setProgress(0);
			} break;
			}
    	}

    	static public String GetTorrentStateName(Activity activity, int StateNum){
    		String txt_state = activity.getString(R.string.text_torrent_state_undefined);
    		if(StateNum >= 0 && StateNum < 10){
	    		TorrentState state = TorrentState.values()[StateNum];
	    		switch (state){
				case queued_for_checking: txt_state = activity.getString(R.string.text_torrent_state_queued_for_checking); break; 
				case checking_files: txt_state = activity.getString(R.string.text_torrent_state_checking_files); break;
				case downloading_metadata: txt_state = activity.getString(R.string.text_torrent_state_downloading_metadata); break;
				case downloading: txt_state = activity.getString(R.string.text_torrent_state_downloading); break;
				case finished: txt_state = activity.getString(R.string.text_torrent_state_finished); break;
				case seeding: txt_state = activity.getString(R.string.text_torrent_state_seeding); break;
				case allocating: txt_state = activity.getString(R.string.text_torrent_state_allocating); break;
				case checking_resume_data: txt_state = activity.getString(R.string.text_torrent_state_checking_resume_data); break;
				case paused: txt_state = activity.getString(R.string.text_torrent_state_paused); break;
				case queued: txt_state = activity.getString(R.string.text_torrent_state_queued); break;
				default: txt_state = activity.getString(R.string.text_torrent_state_undefined); break;
	    		}
    		}
    		return txt_state;
    	}
    	
    	void SetTorrentState(){
    		String txt_state = GetTorrentStateName(this, mTorrentState);
    		if(!mTextViewTorrentState.getText().equals(txt_state)){
	            mTextViewTorrentState.setText(txt_state);
    		}
    	}
    	
    	void SetCommonStatus()
    	{
			if(mTorrentStatus != null && mSessionStatus != null)
				mTextViewCommonStatus.setText("TORRENT STATUS:\n" + mTorrentStatus +  "SESSION STATUS:\n" + mSessionStatus);
			else
				mTextViewCommonStatus.setText(R.string.text_common_status);    		
    	}
    	
    	@Override
    	protected void onResume() {
    		super.onResume();
    		if(DownloaderApp.ExitState){
    			if(DownloaderApp.DownloadServiceMode)
    				DownloaderApp.FinalCloseApplication(this);
    			else
    				DownloaderApp.CloseApplication(this);    			
    		}
            if(DownloaderApp.TorrentFullFileName.equals("undefined"))
            	setTitle(R.string.torrent_file_undefined);
            else
            {
				boolean open_result= false;
            	mTorrentContentName = LibTorrents.GetTorrentName(DownloaderApp.TorrentFullFileName);
				if(mTorrentContentName != null && mTorrentContentName.length() > 0){
					mTorrentTotalSize = LibTorrents.GetTorrentSize(DownloaderApp.TorrentFullFileName);
					if(mTorrentTotalSize >= 0){
		        		File file = new File(DownloaderApp.TorrentFullFileName);
		        		String fileName = file.getName();
						setTitle(fileName);
						open_result = true;
					}
				}
				if(open_result==false){
					DownloaderApp.TorrentFullFileName = new String("undefined");
					setTitle(R.string.torrent_file_undefined);
					Toast.makeText(this, getString(R.string.open_torrent_file_error), Toast.LENGTH_LONG).show();
				}
            }
    	}    	
    	@Override
    	protected void onPause() {
    		super.onPause();
    		SaveControllerState();
	    	if(mAdRefreshTimer != null){
	    		mAdRefreshTimer.cancel(); 
	    		mAdRefreshTimer = null;
	    	}
	    	if(mStatusThread != null){
	    		mStopProgress = true;
	    		mStatusThread = null;
	    	}
    	}
    	@Override
	    protected void onRestart() {
	    	super.onRestart();
	    	RestoreControllerState();
	    	mAdRefreshTimer = new Timer();
	    	mAdRefreshTimer.schedule(new AdRefreshTimerTask(), 0, mAdRefreshTime);
			mStopProgress = false;
	    	mStatusThread = new Thread(mStatusThreadRunnable);
	        mStatusThread.start();                                        
	    }
    	@Override
        protected void onDestroy() {
    		mStopProgress = true;
            super.onDestroy();            
            doUnbindService();
    		//RutrackerDownloaderApp.AnalyticsTracker.dispatch();
        }                
    	public static String CopyTorrentFiles(String newName, String oldName){    		
    		String result =  DownloaderApp.TorrentFullFileName;
    		try{			
				URI torrentFullName =  new URI(DownloaderApp.DefaultTorrentSavePath + newName);
				String filepath = torrentFullName.getPath();
				if (filepath != null) {
					File newFile =  new File(filepath);
					File oldFile = new File(oldName); 
					if(newFile != null && oldFile != null){
						if(DownloaderApp.CopyFile(oldFile, newFile)){
							result = filepath;
						}
					}
				}
    		} catch (Exception ex){
    			Log.e(DownloaderApp.TAG, ex.toString());
    	    }
    		return result;
    	}        
    	public static void DeleteFile(String FullFileName){
			File file =  new File(FullFileName);
			if(file != null)
				file.delete();    		
    	}
		public void OnClickButtonStartDownload(View v) {
        	if(mIsBoundService){
        		try{
        			FileInputStream fis = new FileInputStream(DownloaderApp.TorrentFullFileName); 
            		fis.close();  		
            	}
            	catch(Exception ex){
                    Toast.makeText(Controller.this, R.string.error_torrent_file_absent, Toast.LENGTH_SHORT).show();
                    return;
            	}
        		if(mTorrentSavePath.length() < 3) mTorrentSavePath = DownloadPreferencesScreen.GetTorrentSavePath(this); 
            	String tempName = CopyTorrentFiles("downloader_temp.torrent",DownloaderApp.TorrentFullFileName);
            	//0-storage_mode_allocate
            	//1-storage_mode_sparse
            	//2-storage_mode_compact
            	int storageMode = StorageMode;
            	//if(StorageMode == 0) storageMode = 1;
            	boolean res = LibTorrents.AddTorrent(mTorrentSavePath, tempName, storageMode);
            	if(!tempName.equals(DownloaderApp.TorrentFullFileName))
            		DeleteFile(tempName);
        		if(res == true) SetControllerState(ControllerState.Started);
        	}
		}		
        public void OnClickButtonStopDownload(View v) {
        	if(mIsBoundService){
        		SetControllerState(ControllerState.Stopped);
            	LibTorrents.RemoveTorrent(mTorrentContentName);
    	    	mStopHandler = new StopHandler();
    			mStopMessage =  mStopHandler.obtainMessage();
    			mStopHandler.sendMessageDelayed(mStopMessage, 500);
        	}
        }         
        private class StopHandler extends Handler {	
    	    @Override
    		public void handleMessage(Message message) {
    	    	SetControllerState(ControllerState.Stopped);
    	    }
        } 
		public void OnClickButtonTorrentManager(View v){
			DownloaderApp.OpenDownloaderActivity(this);
			finish();
		}
		public void OnClickButtonStorageMode(View v){
			Toast.makeText(this, getString(R.string.storage_mode_comment), Toast.LENGTH_LONG).show();
    		//StorageMode:
    		//0-storage_mode_allocate
    		//1-storage_mode_sparse
    		//2-storage_mode_compact
			if(mCheckBoxStorageMode.isChecked())
				StorageMode = 0;
			else
				StorageMode = 2;
		}
		public void OnClickButtonPauseDownload(View v){
        	if(mIsBoundService){
            	LibTorrents.PauseTorrent(mTorrentContentName);
        		SetControllerState(ControllerState.Paused);
        	}
		}       
        public void OnClickButtonResumeDownload(View v){
        	if(mIsBound && mIsBoundService){
            	LibTorrents.ResumeTorrent(mTorrentContentName);
        		SetControllerState(ControllerState.Started);
        	}
        }
        public void OnClickButtonSelectFiles(View v){
        	if(mIsBoundService){
	        	Intent intent = new Intent(Intent.ACTION_VIEW);
	        	TorrentFilesList.TORRENT_FILES = LibTorrents.GetTorrentFiles(mTorrentContentName);
	        	TorrentFilesList.FILES_PRIORITY = LibTorrents.GetTorrentFilesPriority(mTorrentContentName);
	        	intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);
	        	intent.setClassName(this, TorrentFilesList.class.getName());
	        	startActivityForResult(intent,SELECT_FILE_ACTIVITY);
        	}
        }        
        private ServiceConnection mConnection = new ServiceConnection(){
           
        	public void onServiceConnected(ComponentName className, IBinder service) {
                mBoundService = ((DownloadService.LocalBinder)service).getService(); 
                if(mBoundService == null) mIsBoundService = false;
                else mIsBoundService = true;
//                if(mIsBoundService){
//                    Toast.makeText(Controller.this, R.string.service_connected, Toast.LENGTH_SHORT).show();
//                }
            }
            public void onServiceDisconnected(ComponentName className) {
            	mIsBoundService = false;
            	mBoundService = null;
//                Toast.makeText(Controller.this, R.string.service_disconnected, Toast.LENGTH_SHORT).show();
                SetControllerState(ControllerState.Stopped);
            }
        };        
        void doBindService() {
        	if (!mIsBound) {
	            bindService(new Intent(Controller.this, DownloadService.class), mConnection, Context.BIND_AUTO_CREATE);
	            mIsBound = true;
        	}
        }        
        void doUnbindService() {
            if (mIsBound) {
             	// Detach our existing connection.
                unbindService(mConnection);
                mIsBound = false;
            }
        }
        @Override
    	protected void onActivityResult(int requestCode, int resultCode, Intent data) {        	
			if(requestCode == SELECT_FILE_ACTIVITY){
                if(mIsBoundService){
                	if(TorrentFilesList.APPLY)
                		LibTorrents.SetTorrentFilesPriority(TorrentFilesList.FILES_PRIORITY, mTorrentContentName);	    		                	
                }
			}
			else{
	        	switch(ActivityResultType.getValue(resultCode))
	    		{
	    		case RESULT_DOWNLOADER:
	    			DownloaderApp.OpenDownloaderActivity(this);
	    			finish();
	    			return;
	    		case RESULT_MAIN:
	    			setResult(resultCode);
	    			finish();
	    			return;
	    		case RESULT_EXIT:
	    			if(DownloaderApp.DownloadServiceMode)
	    				DownloaderApp.FinalCloseApplication(this);
	    			else
	    				DownloaderApp.CloseApplication(this);
	    			return;
	    		};
			}
			super.onActivityResult(requestCode, resultCode, data);
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
//			RutrackerDownloaderApp.ActivateTorrentFileList = true;
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
//			RutrackerDownloaderApp.ActivateTorrentFileList = true;
		}
		public void onPresentScreen(Ad ad) {
			Log.v(DownloaderApp.TAG, "AdMob onLeaveApplication");			
		}
		public void onReceiveAd(Ad ad) {
			Log.v(DownloaderApp.TAG, "AdMob onReceiveAd");						
		}				
    }
}
