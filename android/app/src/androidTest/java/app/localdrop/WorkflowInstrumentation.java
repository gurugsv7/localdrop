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
