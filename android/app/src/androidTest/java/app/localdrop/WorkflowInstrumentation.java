package app.localdrop;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/** On-device integration runner; no testing libraries and no release-app test hooks. */
public final class WorkflowInstrumentation extends Instrumentation {
    private Bundle args;
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); args=arguments;start(); }
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            Context c=getTargetContext();
            if(!c.getSharedPreferences("localdrop",0).contains("pair")) throw new Exception("Phone is not paired");
            String kind=args.getString("case","text");
            if(kind.equals("widget-video")) {
                Uri videos=android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                Set<Long> beforeVideos=new HashSet<>();
                try(android.database.Cursor cursor=c.getContentResolver().query(videos,new String[]{"_id"},"relative_path=?",new String[]{"Movies/LocalDrop/"},null)) {
                    while(cursor!=null&&cursor.moveToNext())beforeVideos.add(cursor.getLong(0));
                }
                String expected=args.getString("sha256");
                if(expected==null)throw new Exception("Synthetic video hash required");
                android.app.Activity activity=startActivitySync(new Intent(c,ReceiveActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                try {
                    long deadline=android.os.SystemClock.elapsedRealtime()+45000;
                    while(android.os.SystemClock.elapsedRealtime()<deadline) {
                        try(android.database.Cursor cursor=c.getContentResolver().query(videos,new String[]{"_id","is_pending","mime_type","_display_name"},"relative_path=?",new String[]{"Movies/LocalDrop/"},null)) {
                            while(cursor!=null&&cursor.moveToNext())if(!beforeVideos.contains(cursor.getLong(0))&&cursor.getInt(1)==0) {
                                if(!cursor.getString(2).equals("video/mp4"))throw new Exception("Generic Windows video was not classified as MP4");
                                Uri uri=android.content.ContentUris.withAppendedId(videos,cursor.getLong(0));
                                java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");
                                try(java.io.InputStream input=c.getContentResolver().openInputStream(uri)) {
                                    byte[] buffer=new byte[65536];int n;
                                    while((n=input.read(buffer))!=-1)digest.update(buffer,0,n);
                                }
                                StringBuilder hex=new StringBuilder();for(byte value:digest.digest())hex.append(String.format(java.util.Locale.ROOT,"%02x",value&255));
                                if(!hex.toString().equals(expected))throw new Exception("Gallery video bytes differ from laptop original");
                                android.media.MediaMetadataRetriever metadata=new android.media.MediaMetadataRetriever();
                                try {
                                    metadata.setDataSource(c,uri);
                                    if(Long.parseLong(metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION))<500)throw new Exception("Video duration missing");
                                    Bitmap frame=metadata.getFrameAtTime(0);
                                    if(frame==null||frame.getWidth()!=64||frame.getHeight()!=48)throw new Exception("Saved Gallery video is not decodable");
                                    frame.recycle();
                                } finally {metadata.release();}
                                String display=cursor.getString(3),id=display.substring("LocalDrop_".length(),"LocalDrop_".length()+32);
                                File receipt=new File(c.getFilesDir(),"inbox/"+id+"/receipt.json");
                                org.json.JSONObject manifest=new org.json.JSONObject(new String(java.nio.file.Files.readAllBytes(receipt.toPath()),java.nio.charset.StandardCharsets.UTF_8));
                                if(Gallery.INSTANCE.save(c,manifest)!=1)throw new Exception("Repeat video export failed");
                                try(android.database.Cursor repeats=c.getContentResolver().query(videos,new String[]{"_id"},"_display_name=? AND relative_path=?",new String[]{display,"Movies/LocalDrop/"},null)) {
                                    if(repeats==null||repeats.getCount()!=1)throw new Exception("Repeat export duplicated the video");
                                }
                                result.putString("result","Windows file clipboard video received over Wi-Fi; published video/mp4 in Movies/LocalDrop; SHA-256 matches; duration and frame decode passed; repeat export creates no duplicate");
                                finish(-1,result);return;
                            }
                        }
                        Thread.sleep(200);
                    }
                    throw new Exception("No Gallery video after widget receive");
                } finally {runOnMainSync(activity::finish);}
            }
            if(kind.equals("widget-gallery")) {
                Set<Long> beforeImages=new HashSet<>();
                Uri images=android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                try(android.database.Cursor cursor=c.getContentResolver().query(images,new String[]{"_id"},"relative_path=?",new String[]{"Pictures/LocalDrop/"},null)) {
                    while(cursor!=null&&cursor.moveToNext())beforeImages.add(cursor.getLong(0));
                }
                android.app.Activity activity=startActivitySync(new Intent(c,ReceiveActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                try {
                    long deadline=android.os.SystemClock.elapsedRealtime()+30000;
                    while(android.os.SystemClock.elapsedRealtime()<deadline) {
                        try(android.database.Cursor cursor=c.getContentResolver().query(images,new String[]{"_id","is_pending","mime_type"},"relative_path=?",new String[]{"Pictures/LocalDrop/"},null)) {
                            while(cursor!=null&&cursor.moveToNext())if(!beforeImages.contains(cursor.getLong(0))&&cursor.getInt(1)==0) {
                                try(java.io.InputStream input=c.getContentResolver().openInputStream(android.content.ContentUris.withAppendedId(images,cursor.getLong(0)))) {
                                    Bitmap image=android.graphics.BitmapFactory.decodeStream(input);
                                    if(image==null||image.getWidth()!=2||image.getHeight()!=3||image.getPixel(0,0)!=0xff247a62)throw new Exception("Received Gallery image differs from laptop clipboard");
                                    image.recycle();
                                }
                                result.putString("result","Widget receive panel fetched the laptop bitmap over Wi-Fi and published an exact-pixel PNG in Pictures/LocalDrop");finish(-1,result);return;
                            }
                        }
                        Thread.sleep(200);
                    }
                    throw new Exception("No new Gallery image after widget receive");
                } finally {runOnMainSync(activity::finish);}
            }
            if(kind.equals("gallery-existing")) {
                int saved=Gallery.INSTANCE.saveExisting(c);
                result.putString("result",saved+" previously received photos/videos are available in Gallery LocalDrop");
                finish(-1,result);return;
            }
            if(kind.equals("gallery")) {
                String id=java.util.UUID.randomUUID().toString().replace("-","");
                File folder=new File(c.getFilesDir(),"inbox/"+id);folder.mkdirs();
                Bitmap original=Bitmap.createBitmap(2,3,Bitmap.Config.ARGB_8888);original.eraseColor(0xff247a62);
                try(FileOutputStream output=new FileOutputStream(new File(folder,"000_test.png"))){original.compress(Bitmap.CompressFormat.PNG,100,output);}
                original.recycle();
                byte[] bmp=new byte[78];java.nio.ByteBuffer b=java.nio.ByteBuffer.wrap(bmp).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                b.put((byte)'B').put((byte)'M').putInt(78).putInt(0).putInt(54).putInt(40).putInt(2).putInt(3).putShort((short)1).putShort((short)24).putInt(0).putInt(24);
                for(int y=0;y<3;y++)for(int x=0;x<2;x++){int p=54+y*8+x*3;bmp[p]=0x62;bmp[p+1]=0x7a;bmp[p+2]=0x24;}
                try(FileOutputStream output=new FileOutputStream(new File(folder,"001_clipboard.bmp"))){output.write(bmp);}
                org.json.JSONObject m=new org.json.JSONObject().put("id",id).put("text",org.json.JSONObject.NULL).put("items",new org.json.JSONArray()
                    .put(new org.json.JSONObject().put("name","test.png").put("mime","image/png"))
                    .put(new org.json.JSONObject().put("name","clipboard.bmp").put("mime","image/bmp")));
                try(FileOutputStream output=new FileOutputStream(new File(folder,"receipt.json"))){output.write(m.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
                if(Gallery.INSTANCE.save(c,m)!=2 || Gallery.INSTANCE.save(c,m)!=2)throw new Exception("Gallery export failed");
                int count=0;
                try(android.database.Cursor cursor=c.getContentResolver().query(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    new String[]{"_id","mime_type","is_pending","relative_path"},"_display_name LIKE ?",new String[]{"LocalDrop_"+id+"_%"},null)) {
                    while(cursor!=null&&cursor.moveToNext()) {
                        if(!cursor.getString(1).equals("image/png")||cursor.getInt(2)!=0||!cursor.getString(3).equals("Pictures/LocalDrop/"))throw new Exception("Gallery metadata incorrect");
                        Uri uri=android.content.ContentUris.withAppendedId(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,cursor.getLong(0));
                        try(java.io.InputStream input=c.getContentResolver().openInputStream(uri)) {
                            Bitmap pixels=android.graphics.BitmapFactory.decodeStream(input);
                            if(pixels==null||pixels.getWidth()!=2||pixels.getHeight()!=3||pixels.getPixel(0,0)!=0xff247a62)throw new Exception("Gallery pixels changed");
                            pixels.recycle();
                        }
                        count++;
                    }
                }
                if(count!=2)throw new Exception("Export duplicated images");
                Bitmap preview=ImagePreview.INSTANCE.decode(new File(folder,"001_clipboard.bmp"));
                if(preview.getPixel(0,0)!=0xff247a62)throw new Exception("Preview pixels changed");preview.recycle();
                result.putString("result","PNG and clipboard BMP published as visible Gallery PNGs with exact pixels, no duplicates on repeat export; internal BMP preview decoded");
                finish(-1,result);return;
            }
            if(kind.equals("widget")) {
                boolean found=false;
                for(android.appwidget.AppWidgetProviderInfo provider:android.appwidget.AppWidgetManager.getInstance(c).getInstalledProviders()) {
                    if(provider.provider.getClassName().equals("app.localdrop.ReceiveWidget")) {
                        found=true;
                        if(provider.updatePeriodMillis!=0)throw new Exception("Widget must not poll");
                        android.view.View preview=new android.widget.RemoteViews(c.getPackageName(),provider.initialLayout).apply(c,null);
                        int w=(int)(180*c.getResources().getDisplayMetrics().density),h=(int)(80*c.getResources().getDisplayMetrics().density);
                        preview.measure(android.view.View.MeasureSpec.makeMeasureSpec(w,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(h,android.view.View.MeasureSpec.EXACTLY));
                        preview.layout(0,0,w,h);
                        Bitmap image=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
                        preview.draw(new android.graphics.Canvas(image));
                        File fixture=new File(c.getFilesDir(),"inbox/workflow-fixtures");fixture.mkdirs();
                        try(FileOutputStream output=new FileOutputStream(new File(fixture,"receive-widget.png"))){image.compress(Bitmap.CompressFormat.PNG,100,output);}
                        image.recycle();
                    }
                }
                if(!found)throw new Exception("Receive widget is not registered");
                String expected=args.getString("text");
                if(expected==null)throw new Exception("Expected synthetic clipboard text is required");
                android.app.Activity activity=startActivitySync(new Intent(c,ReceiveActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                try {
                    long deadline=android.os.SystemClock.elapsedRealtime()+30000;
                    while(android.os.SystemClock.elapsedRealtime()<deadline) {
                        final boolean[] matched={false};
                        runOnMainSync(()->{
                            if(activity.hasWindowFocus()) {
                                android.content.ClipData clip=((android.content.ClipboardManager)c.getSystemService(Context.CLIPBOARD_SERVICE)).getPrimaryClip();
                                matched[0]=clip!=null && clip.getItemCount()>0 && expected.contentEquals(clip.getItemAt(0).coerceToText(c));
                            }
                        });
                        if(matched[0]) {
                            result.putString("result","Widget registered with no polling; focused receive panel fetched exact Windows clipboard text over the paired connection");
                            finish(-1,result);return;
                        }
                        Thread.sleep(200);
                    }
                    throw new Exception("Receive panel did not publish the expected clipboard text");
                } finally {runOnMainSync(activity::finish);}
            }
            File outbox=new File(c.getFilesDir(),"outbox");
            Set<String> before=new HashSet<>();
            File[] existing=outbox.listFiles();if(existing!=null) for(File f:existing) before.add(f.getName());
            Intent share=new Intent(Intent.ACTION_SEND).setClassName("app.localdrop","app.localdrop.ShareActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if(kind.equals("selection")) {
                boolean registered=false;
                for(android.content.pm.ResolveInfo info:c.getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain"),android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)) {
                    if(info.activityInfo.name.equals("app.localdrop.ProcessTextActivity")&&info.loadLabel(c.getPackageManager()).toString().equals("LocalDrop")) registered=true;
                }
                if(!registered)throw new Exception("LocalDrop is not registered in Android selected-text actions");
                share.setAction(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
                    .setClassName("app.localdrop","app.localdrop.ProcessTextActivity")
                    .putExtra(Intent.EXTRA_PROCESS_TEXT,"Selected text → LocalDrop\nLine two: café ✓")
                    .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY,false);
            } else if(kind.equals("clipboard")) {
                ((android.content.ClipboardManager)c.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(android.content.ClipData.newPlainText("LocalDrop test","Copied text → laptop\nCafé ✓"));
                c.startActivity(new Intent().setClassName("app.localdrop","app.localdrop.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                // Deliberately manual: UiAutomation temporarily registers accessibility
                // automation, which can conflict with payment-app security checks.
                Bundle prompt=new Bundle();prompt.putString("manual_action","Tap Send copied text in LocalDrop");sendStatus(1,prompt);
            } else if(kind.equals("text")) {
                share.setType("text/plain").putExtra(Intent.EXTRA_TEXT,args.getString("text","LocalDrop physical Android share test"));
            } else {
                File fixture=new File(c.getFilesDir(),"inbox/workflow-fixtures");fixture.mkdirs();
                ArrayList<Uri> uris=new ArrayList<>();
                int count=kind.equals("multi")?2:1;
                for(int n=0;n<count;n++) {
                    File file=new File(fixture,"share-test-"+n+".png");
                    Bitmap bitmap=Bitmap.createBitmap(64+n,48+n,Bitmap.Config.ARGB_8888);
                    bitmap.eraseColor(n==0?0xff247a62:0xffe5a642);
                    try(FileOutputStream stream=new FileOutputStream(file)) {bitmap.compress(Bitmap.CompressFormat.PNG,100,stream);stream.getFD().sync();}
                    bitmap.recycle();
                    uris.add(new Uri.Builder().scheme("content").authority("app.localdrop.received").appendPath("workflow-fixtures").appendPath(file.getName()).build());
                }
                share.setType("image/png");
                if(count==1) share.putExtra(Intent.EXTRA_STREAM,uris.get(0));
                else share.setAction(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM,uris);
            }
            if(!kind.equals("clipboard"))c.startActivity(share);
            long deadline=android.os.SystemClock.elapsedRealtime()+45000;
            while(android.os.SystemClock.elapsedRealtime()<deadline) {
                File[] pending=outbox.listFiles();
                if(pending!=null) for(File f:pending) if(!before.contains(f.getName())&&new File(f,"sent").exists()) {
                    result.putString("case",kind);result.putString("transfer_id",f.getName());
                    result.putString("result","Windows acknowledged the share after clipboard publication");finish(-1,result);return;
                }
                Thread.sleep(250);
            }
            throw new Exception("No Windows acknowledgement within 45 seconds: "+c.getSharedPreferences("localdrop",0).getString("status","No status"));
        } catch(Throwable e) {result.putString("failure",e.toString());finish(0,result);}
    }
}
