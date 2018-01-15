package com.audioStreaming;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.BitmapFactory;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnCompletionListener;
import android.media.MediaPlayer.OnErrorListener;
import android.media.MediaPlayer.OnInfoListener;
import android.media.MediaPlayer.OnPreparedListener;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.TaskStackBuilder;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.widget.RemoteViews;

import com.spoledge.aacdecoder.MultiPlayer;
import com.spoledge.aacdecoder.PlayerCallback;

import android.util.Log;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import android.graphics.Bitmap;
import java.net.URI;
import android.app.Notification;

import android.os.PowerManager;


public class Signal extends Service implements OnErrorListener,
        OnCompletionListener,
        OnPreparedListener,
        OnInfoListener,
        PlayerCallback {


    // Notification
    private Class<?> clsActivity;
    private static final int NOTIFY_ME_ID = 696969;
    private NotificationCompat.Builder notifyBuilder;
    private NotificationManager notifyManager = null;
    public static RemoteViews remoteViews;
    private MultiPlayer aacPlayer;

    private static final int AAC_BUFFER_CAPACITY_MS = 2500;
    private static final int AAC_DECODER_CAPACITY_MS = 700;

    public static final String BROADCAST_PLAYBACK_STOP = "stop",
            BROADCAST_PLAYBACK_PLAY = "pause",
            BROADCAST_EXIT = "exit";

    private final Handler handler = new Handler();
    private final IBinder binder = new RadioBinder();
    private final SignalReceiver receiver = new SignalReceiver(this);
    private Context context;
    private String streamingURL;
    public boolean isPlaying = false;
    private boolean isPreparingStarted = false;
    private EventsReceiver eventsReceiver;
    private ReactNativeAudioStreamingModule module;

    private TelephonyManager phoneManager;
    private PhoneListener phoneStateListener;

    private boolean isButtonStopped = false;

    public void setData(Context context, ReactNativeAudioStreamingModule module) {
        this.context = context;
        this.clsActivity = module.getClassActivity();
        this.module = module;

        this.eventsReceiver = new EventsReceiver(this.module);


        registerReceiver(this.eventsReceiver, new IntentFilter(Mode.CREATED));
        registerReceiver(this.eventsReceiver, new IntentFilter(Mode.DESTROYED));
        registerReceiver(this.eventsReceiver, new IntentFilter(Mode.STARTED));
        registerReceiver(this.eventsReceiver, new IntentFilter(Mode.CONNECTING));
        registerReceiver(this.eventsReceiver, new IntentFilter(Mode.START_PREPARING));
        registerReceiver(this.eventsReceiver, new IntentFilter(Mode.PREPARED));
        registerReceiver(this.eventsReceiver, new IntentFilter(Mode.PLAYING));
        registerReceiver(this.eventsReceiver, new IntentFilter(Mode.STOPPED));
        registerReceiver(this.eventsReceiver, new IntentFilter(Mode.COMPLETED));
        registerReceiver(this.eventsReceiver, new IntentFilter(Mode.ERROR));
        registerReceiver(this.eventsReceiver, new IntentFilter(Mode.BUFFERING_START));
        registerReceiver(this.eventsReceiver, new IntentFilter(Mode.BUFFERING_END));
        registerReceiver(this.eventsReceiver, new IntentFilter(Mode.METADATA_UPDATED));
        registerReceiver(this.eventsReceiver, new IntentFilter(Mode.ALBUM_UPDATED));
        registerReceiver(this.eventsReceiver, new IntentFilter(Mode.RETRYING));


        this.phoneStateListener = new PhoneListener(this.module);
        this.phoneManager = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        if (this.phoneManager != null) {
            this.phoneManager.listen(this.phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);
        }


    }

    @Override
    public void onCreate() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BROADCAST_PLAYBACK_STOP);
        intentFilter.addAction(BROADCAST_PLAYBACK_PLAY);
        intentFilter.addAction(BROADCAST_EXIT);
        registerReceiver(this.receiver, intentFilter);


        try {
            this.aacPlayer = new MultiPlayer(this, AAC_BUFFER_CAPACITY_MS, AAC_DECODER_CAPACITY_MS);
            aacPlayer.setMetadataCharEnc("windows-1251"); // more.fm patch
        } catch (UnsatisfiedLinkError e) {
            e.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
        }


        this.notifyManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        try {
            java.net.URL.setURLStreamHandlerFactory(new java.net.URLStreamHandlerFactory() {
                public java.net.URLStreamHandler createURLStreamHandler(String protocol) {
                    if (":0".equals(protocol)) {
                        return new com.spoledge.aacdecoder.IcyURLStreamHandler();
                    }
                    return null;
                }
            });
        } catch (Throwable t) {

        }

        sendBroadcast(new Intent(Mode.CREATED));
    }

    public void setURLStreaming(String streamingURL) {
        this.streamingURL = streamingURL;
    }

    public void play() {

        if ( isConnected() ) {
            while ( isPlaying ) {
                try {
                    Log.d("Play","Wait 0.5 sec for async stop");
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Log.e("Play:", e.toString());
                }
            }
            this.prepare();
        } else {
            sendBroadcast(new Intent(Mode.STOPPED));
            return;
        }
        Log.d("Play","Set isPlaying to true as in original package");
        this.isPlaying = true;
        this.isButtonStopped = false;
    }

    public void stop() {

        //Log.d("Stop","Stopped by user");

        this.isPreparingStarted = false;
        this.isButtonStopped = true;

        if (this.isPlaying) {
            //this.isPlaying = false; // Это будет выставлено позже, в callback playerStopped.
            this.aacPlayer.stop();
        }
//        sendBroadcast(new Intent(Mode.STOPPED)); // Это тоже.
    }

    public void stopByBroadcastExit() {
        Log.d("Stop","Stopped by broadcast exit signal");
        this.module.statePlay = false;
        this.stop();
    }

    public NotificationManager getNotifyManager() {
        return notifyManager;
    }

    public class RadioBinder extends Binder {
        public Signal getService() {
            return Signal.this;
        }
    }

    private Bitmap getBitmapFromURL (String src) {
        try {
            URL url = new URL(src);
            URI uri = new URI(url.getProtocol(), url.getUserInfo(), url.getHost(), url.getPort(), url.getPath(), url.getQuery(), url.getRef());
            url = uri.toURL();

            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setDoInput(true);
            connection.connect();
            InputStream input = connection.getInputStream();
            Bitmap myBitmap = BitmapFactory.decodeStream(input);
            return myBitmap;
        } catch (IOException e) {
            Log.e("getBitmap",e.toString());
            return null;
        } catch (URISyntaxException se) {
            Log.e("getBitmap",se.toString());
            return null;
        }
    }

    public void nowPlayInfo(String coverURL, String channel, String text ) {
        //Log.d("nowPlayInfo",coverURL);
        Bitmap draw = getBitmapFromURL(coverURL);
        if (draw != null ) {
            //Log.d("NowPlayInfo","Setting bitmap");
            remoteViews.setBitmap(R.id.streaming_icon, "setImageBitmap", draw);
        }
        if (!text.equals("")) {
            remoteViews.setTextViewText(R.id.song_name_notification, text);
        }
        if (!channel.equals("")) {
            remoteViews.setTextViewText(R.id.album_notification, channel);
        }

        if ( notifyBuilder != null ) {
            notifyBuilder.setContent(remoteViews);
            startForeground(NOTIFY_ME_ID, notifyBuilder.build());
        }
    }

    public void showTextNotification(String text) {
        remoteViews.setTextViewText(R.id.song_name_notification, text);
        if ( notifyBuilder != null ) {
            notifyBuilder.setContent(remoteViews);
            startForeground(NOTIFY_ME_ID, notifyBuilder.build());
        }
    }

    public void showNotification() {
        remoteViews = new RemoteViews(context.getPackageName(), R.layout.streaming_notification_player);
        notifyBuilder = new NotificationCompat.Builder(this.context)
                .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off) // TODO Use app icon instead
                .setContentText("")
                .setOngoing(true)
                .setContent(remoteViews);

        Intent resultIntent = new Intent(this.context, this.clsActivity);
        resultIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        resultIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        TaskStackBuilder stackBuilder = TaskStackBuilder.create(this.context);
        stackBuilder.addParentStack(this.clsActivity);
        stackBuilder.addNextIntent(resultIntent);

        PendingIntent resultPendingIntent = stackBuilder.getPendingIntent(0,
                PendingIntent.FLAG_UPDATE_CURRENT);

        notifyBuilder.setContentIntent(resultPendingIntent);
       // remoteViews.setOnClickPendingIntent(R.id.btn_streaming_notification_play, makePendingIntent(BROADCAST_PLAYBACK_PLAY));
       // remoteViews.setOnClickPendingIntent(R.id.btn_streaming_notification_stop, makePendingIntent(BROADCAST_EXIT));
        remoteViews.setOnClickPendingIntent(R.id.btn_streaming_notification_stop, makePendingIntent(BROADCAST_EXIT));

        notifyManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        //notifyManager.notify(NOTIFY_ME_ID, notifyBuilder.build());

        Notification notification = notifyBuilder.build();
        startForeground(NOTIFY_ME_ID, notification);

        // TODO foreground service and stop foreground service.

    }

    private PendingIntent makePendingIntent(String broadcast) {
        Intent intent = new Intent(broadcast);
        return PendingIntent.getBroadcast(this.context, 0, intent, 0);
    }

    public void clearNotification() {
        if (notifyManager != null)
            //notifyManager.cancel(NOTIFY_ME_ID);
            stopForeground(true);
    }

    public void exitNotification() {
        if ( notifyManager != null ) {
          notifyManager.cancelAll();
          clearNotification();
          notifyBuilder = null;
          notifyManager = null;
        }
    }

    public boolean isConnected() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo netInfo = cm.getActiveNetworkInfo();
        if (netInfo != null && netInfo.isConnectedOrConnecting()) {
            return true;
        }
        return false;
    }

    public void prepare() {
        /* ------Station- buffering-------- */
        this.isPreparingStarted = true;
        sendBroadcast(new Intent(Mode.START_PREPARING));
        try {
            Log.d("PlayAsync",this.streamingURL);
            this.aacPlayer.playAsync(this.streamingURL);
        } catch (Exception e) {
            e.printStackTrace();
            stop();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (this.isPlaying) {
            sendBroadcast(new Intent(Mode.PLAYING));
        } else if (this.isPreparingStarted) {
            sendBroadcast(new Intent(Mode.START_PREPARING));
        } else {
            sendBroadcast(new Intent(Mode.STARTED));
        }

        return Service.START_STICKY;
    }

    // Для того, что бы этот вызов сработал надо
    // а) сделать не только bindService, но и startService перед этим
    // б) вернуть START_STICKY из onStartCommand
    // в) прописать stopWithTask в манифесте = false.
    public void onTaskRemoved (Intent rootIntent) {
      // Log.d("OnTaskRemoved","Callback");
      this.exitNotification();
    }

    @Override
    public void onPrepared(MediaPlayer mediaPlayer) {
        this.isPreparingStarted = false;
        sendBroadcast(new Intent(Mode.PREPARED));
        //mediaPlayer.setWakeMode(context,PowerManager.PARTIAL_WAKE_LOCK);
    }

    @Override
    public void onCompletion(MediaPlayer mediaPlayer) {
        this.isPlaying = false;
        this.aacPlayer.stop();
        sendBroadcast(new Intent(Mode.COMPLETED));
    }

    @Override
    public boolean onInfo(MediaPlayer mp, int what, int extra) {
        if (what == 701) {
            this.isPlaying = false;
            sendBroadcast(new Intent(Mode.BUFFERING_START));
        } else if (what == 702) {
            this.isPlaying = true;
            sendBroadcast(new Intent(Mode.BUFFERING_END));
        }
        return false;
    }

    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        Log.d("Error","What: " + what + "Extra: " + extra);
        switch (what) {
            case MediaPlayer.MEDIA_ERROR_NOT_VALID_FOR_PROGRESSIVE_PLAYBACK:
                Log.v("ERROR", "MEDIA ERROR NOT VALID FOR PROGRESSIVE PLAYBACK "	+ extra);
                break;
            case MediaPlayer.MEDIA_ERROR_SERVER_DIED:
                Log.v("ERROR", "MEDIA ERROR SERVER DIED " + extra);
                break;
            case MediaPlayer.MEDIA_ERROR_UNKNOWN:
                Log.v("ERROR", "MEDIA ERROR UNKNOWN " + extra);
                break;
        }
        sendBroadcast(new Intent(Mode.ERROR));
        return false;
    }

    @Override
    public void playerStarted() {
        //  TODO
    }

    @Override
    public void playerPCMFeedBuffer(boolean isPlaying, int bufSizeMs, int bufCapacityMs) {
        if (isPlaying) {
            this.isPreparingStarted = false;
            if (bufSizeMs < 500) {
                this.isPlaying = false;
                sendBroadcast(new Intent(Mode.BUFFERING_START));
                //buffering
            } else {
                this.isPlaying = true;
                sendBroadcast(new Intent(Mode.PLAYING));
                //playing
            }
        } else {
            //buffering
            this.isPlaying = false;
            sendBroadcast(new Intent(Mode.BUFFERING_START));
        }
    }

    @Override
    public void playerException(final Throwable t) {
        Log.e("Error","Player exception occurred!");
        if (this.isButtonStopped) {
            this.isPlaying = false;
            this.isPreparingStarted = false;
            sendBroadcast(new Intent(Mode.ERROR));
            return;
        }
        sendBroadcast(new Intent(Mode.RETRYING));
        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            Log.e("playerException",e.toString());
        }
        prepare();
        /*
          this.isPlaying = false;
          this.isPreparingStarted = false;
          sendBroadcast(new Intent(Mode.ERROR));
        */

    }

    @Override
    public void playerMetadata(final String key, final String value) {
        Intent metaIntent = new Intent(Mode.METADATA_UPDATED);
        metaIntent.putExtra("key", key);
        metaIntent.putExtra("value", value);
        sendBroadcast(metaIntent);

        if (key != null && key.equals("StreamTitle") ) {
            Log.d("MORE StreamTitle:", value);
            String newValue = this.morefm_replace(value);
            if ( remoteViews != null ) {
              remoteViews.setTextViewText(R.id.song_name_notification, newValue);
              if ( notifyBuilder != null ) {
                notifyBuilder.setContent(remoteViews);
                startForeground(NOTIFY_ME_ID, notifyBuilder.build());
              }
            }
        }
    }

    private String morefm_replace ( String morefm_title) {
      String input = morefm_title;

      String[] items;
      String name;
      int wavIndex = input.lastIndexOf(".wav");
      int mp3Index = input.lastIndexOf(".mp3");
      if ( mp3Index > 0 ) {
        items = input.split(".mp3");
        name = items[1];
      } else if ( wavIndex > 0 ) {
        items = input.split(".wav");
        name = items[1];
      } else {
        name = morefm_title;
        items = new String[1];
        items[0] = morefm_title;
      }
      String newString = name.trim();
      if (newString == "") {
        name = items[0].trim();
      }
      name = name.replace("\\\\Nas\\st","");
      int x = name.lastIndexOf("\\M\\Programs\\");
      if ( x > 0 ) {
        name = name.substring(x);
      }
      name = name.replace("\\t","");
        name = name.trim();
      return ( name );
    }

    @Override
    public void playerAudioTrackCreated(AudioTrack atrack) {
        //  TODO
    }

    @Override
    public void playerStopped(int perf) {
        if (this.isButtonStopped) {
            Log.d("Stop", "Button stop pressed.");
        } else {
            Log.d("Stop", "Player stopped by exception! Do not exit, restart player.");
            // У меня получилось. Это работает. Тут надо попробовать сделать рестарт плееру.
            this.prepare();
            return;
        }
        this.exitNotification();
        this.isPlaying = false;
        this.isPreparingStarted = false;
        sendBroadcast(new Intent(Mode.STOPPED));
        //  TODO
    }

}
