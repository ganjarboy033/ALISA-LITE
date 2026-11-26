package nx;

import android.app.*;
import android.content.*;
import android.os.*;

/** Foreground keep-alive service; downloader queue can bind its lifecycle to this service. */
public class DownloadKeepAliveService extends Service {
  private static final String CHANNEL="alisa_downloads";
  @Override public void onCreate(){super.onCreate(); if(Build.VERSION.SDK_INT>=26){NotificationChannel c=new NotificationChannel(CHANNEL,"Alisa Lite downloads",NotificationManager.IMPORTANCE_LOW); ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(c);}}
  @Override public int onStartCommand(Intent i,int f,int id){Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this); b.setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("Alisa Lite").setContentText("Layanan unduhan aktif").setOngoing(true); startForeground(7413,b.build()); return START_STICKY;}
  @Override public IBinder onBind(Intent i){return null;}
}
