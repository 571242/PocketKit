package cn.xianhua.pocketkit;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Host contract probe for open-with routing; no device or provider claim.
 *
 * <p>The manager deliberately does not register a catch-all ACTION_VIEW filter: being a viewer for
 * every file type put it in every app's "open with" list while it could only ever locate a file it
 * had itself been handed. What it guarantees instead is that an incoming file can be traced back to
 * a folder.
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
        require(managerBlock.contains("android.intent.action.SEND")&&!managerBlock.contains("android.intent.action.VIEW"),"no catch-all VIEW filter; share targets stay");
        require(managerBlock.contains("android:mimeType=\"*/*\""),"share sheet still accepts any type");
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
        System.out.println("ManagerOpenWithContractTest PASS: 12 assertions; no-catch-all-view/send/location/highlight/honest-fallback/folder-entry/identity/dedup/ancestors/volumes/mediastore");
    }
    private static String read(Path root,String relative)throws Exception{return new String(Files.readAllBytes(root.resolve(relative)),StandardCharsets.UTF_8);}
    private static void require(boolean ok,String label){if(!ok)throw new AssertionError(label);}
}
