package cn.xianhua.pocketkit;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Host contract probe for open-with routing; no device or provider claim.
 *
 * <p>The manager itself stays out of every app's "open with" list: its activity only receives
 * shares. Locating a file from such a list is a separate, clearly labelled alias that carries
 * ACTION_VIEW, never declares a priority, and only ever jumps to a real parent folder.
 */
public final class ManagerOpenWithContractTest {
    private ManagerOpenWithContractTest() { }
    public static void main(String[] args)throws Exception{
        Path root=args.length==0?Path.of("."):Path.of(args[0]);
        String activity=read(root,"app/src/main/java/cn/xianhua/pocketkit/ManagerActivity.java");
        String store=read(root,"app/src/main/java/cn/xianhua/pocketkit/ManagerStore.java");
        String algorithms=read(root,"app/src/main/java/cn/xianhua/pocketkit/ManagerAlgorithms.java");
        String manifest=read(root,"app/src/main/AndroidManifest.xml");
        int start=manifest.indexOf("<activity android:name=\".ManagerActivity\"");
        require(start>=0,"manager activity declared");
        String managerBlock=manifest.substring(start,manifest.indexOf("</activity>",start));
        require(managerBlock.contains("android.intent.action.SEND")&&!managerBlock.contains("android.intent.action.VIEW"),"the activity itself stays a share target, not a file viewer");
        require(managerBlock.contains("android:mimeType=\"*/*\""),"share sheet still accepts any type");

        // The locate entry: one labelled alias, VIEW + DEFAULT, content and file URIs, no priority.
        int aliasStart=manifest.indexOf("<activity-alias android:name=\".ManagerLocateActivity\"");
        require(aliasStart>=0,"locate alias declared");
        String aliasBlock=manifest.substring(aliasStart,manifest.indexOf("</activity-alias>",aliasStart));
        require(aliasBlock.contains("android:targetActivity=\".ManagerActivity\""),"alias forwards to the one real manager screen");
        require(aliasBlock.contains("android:label=\"拾用·定位文件位置\""),"alias carries a self-explaining Chinese label");
        require(aliasBlock.contains("android:exported=\"true\""),"alias is reachable from other apps");
        require(aliasBlock.contains("android.intent.action.VIEW")&&aliasBlock.contains("android.intent.category.DEFAULT"),"alias answers plain VIEW from a chooser");
        require(aliasBlock.contains("android:scheme=\"content\"")&&aliasBlock.contains("android:scheme=\"file\"")&&aliasBlock.contains("android:mimeType=\"*/*\""),"alias covers content and file URIs for every type");
        require(!aliasBlock.contains("android:priority"),"alias declares no priority");
        require(!aliasBlock.contains("BROWSABLE"),"alias is not a link target");
        require(!aliasBlock.contains("SEND"),"locate alias does not duplicate the share target");
        // Locating needs nothing beyond the permissions the manager already declares.
        require(!manifest.contains("READ_MEDIA_"),"locating needs no extra media permission");

        require(activity.contains("store.locateNearestReadableParent(incoming.get(0))")&&!activity.contains("menu(incoming.get(0),active)"),"single incoming locates instead of opening by format");
        require(activity.contains("p.highlight=location.target.location()")&&activity.contains("已定位并高亮"),"independent target highlight");
        require(activity.contains("showIncomingNearest")&&activity.contains("未精确定位到文件本身"),"ancestor fallback never claims an exact match");
        require(activity.contains("授权父目录")&&activity.contains("文件详情")&&activity.contains("不会复制文件来冒充原位置"),"honest authorization fallback");
        require(activity.contains("locateFolderOf")&&activity.contains("打开所在位置"),"explicit open-containing-folder entry exists");
        require(store.contains("getCanonicalFile()")&&store.contains("DocumentsContract.getDocumentId(first.uri)")&&!store.contains("first.name.equals(second.name)"),"identity match never guesses by name");
        require(activity.contains("u!=null&&!uris.contains(u)"),"duplicate data/stream URI is one incoming file");
        require(store.contains("child.parent=next")&&store.contains("next.parent=parent"),"located parents retain their navigable ancestor chain");
        require(algorithms.contains("documentVolumeAndPath")&&store.contains("volumeRoot(")&&store.contains("volumeOf("),"non-primary volumes resolve to their own mount point");
        require(store.contains("mediaStoreFile(")&&store.contains("MediaColumns.DATA"),"MediaStore ids resolve to a real path");

        // Reuse of an existing window must still serve the new file, and the title must not lie.
        require(activity.contains("@Override protected void onNewIntent(Intent intent)")&&activity.contains("queueIncoming(intent)"),"a reused window re-routes the incoming intent");
        require(activity.contains("deferredIncoming")&&activity.contains("drainIncoming()"),"an intent arriving mid-operation is queued, not dropped");
        require(activity.contains("boolean locating=Intent.ACTION_VIEW.equals(getIntent().getAction())")&&activity.contains("locating?\"定位文件位置\":\"管理器\""),"VIEW entry is titled as a locate request");
        require(activity.contains("\".manager.share\"")&&activity.contains("临时分享副本"),"our own staged share copy is refused instead of being passed off as a location");
        require(activity.contains("Intent.EXTRA_EXCLUDE_COMPONENTS")&&activity.contains("getPackageName()+\".ManagerLocateActivity\""),"our own chooser cannot offer the locate entry for a staged copy");
        System.out.println("ManagerOpenWithContractTest PASS: 28 assertions; scoped-view-alias/label/schemes/no-priority/no-send/no-extra-permission/locate/highlight/honest-fallback/folder-entry/identity/dedup/ancestors/volumes/mediastore/onNewIntent/queue/title/own-copy/own-chooser");
    }
    private static String read(Path root,String relative)throws Exception{return new String(Files.readAllBytes(root.resolve(relative)),StandardCharsets.UTF_8);}
    private static void require(boolean ok,String label){if(!ok)throw new AssertionError(label);}
}
