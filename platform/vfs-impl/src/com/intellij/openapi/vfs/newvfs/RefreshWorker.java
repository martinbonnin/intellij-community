// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.vfs.newvfs;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.io.FileAttributes;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.InvalidVirtualFileAccessException;
import com.intellij.openapi.vfs.VFileProperty;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.ex.temp.TempFileSystem;
import com.intellij.openapi.vfs.newvfs.events.ChildInfo;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.openapi.vfs.newvfs.impl.FakeVirtualFile;
import com.intellij.openapi.vfs.newvfs.impl.VirtualDirectoryImpl;
import com.intellij.openapi.vfs.newvfs.impl.VirtualFileSystemEntry;
import com.intellij.openapi.vfs.newvfs.persistent.BatchingFileSystem;
import com.intellij.openapi.vfs.newvfs.persistent.PersistentFS;
import com.intellij.util.ArrayUtil;
import com.intellij.util.containers.CollectionFactory;
import com.intellij.util.containers.ContainerUtil;
import it.unimi.dsi.fastutil.objects.ObjectOpenCustomHashSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Consumer;

import static com.intellij.openapi.vfs.newvfs.VfsEventGenerationHelper.LOG;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

final class RefreshWorker {
  private final boolean myIsRecursive;
  private final Deque<NewVirtualFile> myRefreshQueue = new ArrayDeque<>(100);
  private final VfsEventGenerationHelper myHelper = new VfsEventGenerationHelper();
  private volatile boolean myCancelled;
  private final LocalFileSystemRefreshWorker myLocalFileSystemRefreshWorker;
  private int myFullScans, myPartialScans, myProcessed;
  private long myVfsTime, myIoTime;

  RefreshWorker(@NotNull NewVirtualFile refreshRoot, boolean isRecursive) {
    boolean canUseNioRefresher = refreshRoot.isInLocalFileSystem() &&
                                 !(refreshRoot.getFileSystem() instanceof TempFileSystem) &&
                                 Registry.is("vfs.use.nio-based.local.refresh.worker");
    myLocalFileSystemRefreshWorker = canUseNioRefresher ? new LocalFileSystemRefreshWorker(refreshRoot, isRecursive) : null;
    myIsRecursive = isRecursive;
    myRefreshQueue.addLast(refreshRoot);
  }

  @NotNull List<VFileEvent> getEvents() {
    if (myLocalFileSystemRefreshWorker != null) return myLocalFileSystemRefreshWorker.getEvents();
    return myHelper.getEvents();
  }

  void cancel() {
    if (myLocalFileSystemRefreshWorker != null) myLocalFileSystemRefreshWorker.cancel();
    myCancelled = true;
  }

  void scan() {
    if (myLocalFileSystemRefreshWorker != null) {
      myLocalFileSystemRefreshWorker.scan();
      return;
    }

    var t = System.nanoTime();
    NewVirtualFile root = myRefreshQueue.removeFirst();
    NewVirtualFileSystem fs = root.getFileSystem();
    if (root.isDirectory()) {
      fs = PersistentFS.replaceWithNativeFS(fs);
    }
    PersistentFS persistence = PersistentFS.getInstance();

    FileAttributes attributes = fs.getAttributes(root);
    if (attributes == null) {
      myHelper.scheduleDeletion(root);
      root.markClean();
      return;
    }

    checkAndScheduleChildRefresh(fs, persistence, root.getParent(), root, attributes);

    if (root.isDirty() && root.isDirectory() && myRefreshQueue.isEmpty()) {
      queueDirectory(root);
    }

    try {
      processQueue(fs, persistence);
    }
    catch (RefreshCancelledException e) {
      LOG.trace("refresh cancelled");
    }

    t = NANOSECONDS.toMillis(System.nanoTime() - t);
    var retries = (myFullScans + myPartialScans) - myProcessed;
    VfsUsageCollector.logRefreshScan(myFullScans, myPartialScans, retries, t, NANOSECONDS.toMillis(myVfsTime), NANOSECONDS.toMillis(myIoTime));
  }

  private void queueDirectory(NewVirtualFile root) {
    if (root instanceof VirtualDirectoryImpl) {
      myRefreshQueue.addLast(root);
    }
    else {
      LOG.error("not a directory: " + root + " (" + root.getClass() + ')');
    }
  }

  private void processQueue(NewVirtualFileSystem fs, PersistentFS persistence) throws RefreshCancelledException {
    nextDir:
    while (!myRefreshQueue.isEmpty()) {
      VirtualDirectoryImpl dir = (VirtualDirectoryImpl)myRefreshQueue.removeFirst();
      boolean fullSync = dir.allChildrenLoaded();
      boolean succeeded;

      do {
        if (fullSync) myFullScans++; else myPartialScans++;
        myHelper.beginTransaction();
        try {
          succeeded = fullSync ? fullDirRefresh(fs, persistence, dir) : partialDirRefresh(fs, persistence, dir);
        }
        catch (InvalidVirtualFileAccessException e) {
          myHelper.endTransaction(false);
          continue nextDir;
        }
        myHelper.endTransaction(succeeded);
        if (!succeeded && LOG.isTraceEnabled()) LOG.trace("retry: " + dir);
      }
      while (!succeeded);
      myProcessed++;

      if (myIsRecursive) {
        dir.markClean();
      }
    }
  }

  private boolean fullDirRefresh(NewVirtualFileSystem fs, PersistentFS persistence, VirtualDirectoryImpl dir) {
    var t = System.nanoTime();
    Pair<List<String>, List<VirtualFile>> snapshot = LocalFileSystemRefreshWorker.getDirectorySnapshot(dir);
    myVfsTime += System.nanoTime() - t;
    if (snapshot == null) {
      return false;
    }
    List<String> persistedNames = snapshot.getFirst();
    List<VirtualFile> children = snapshot.getSecond();

    t = System.nanoTime();
    Map<String, FileAttributes> childrenWithAttributes = fs instanceof BatchingFileSystem ? ((BatchingFileSystem)fs).listWithAttributes(dir) : null;
    String[] listDir = childrenWithAttributes != null ? ArrayUtil.toStringArray(childrenWithAttributes.keySet()) : fs.list(dir);
    myIoTime += System.nanoTime() - t;
    String[] upToDateNames = VfsUtil.filterNames(listDir);
    Set<String> newNames = new HashSet<>(upToDateNames.length);
    ContainerUtil.addAll(newNames, upToDateNames);
    if (dir.allChildrenLoaded() && children.size() < upToDateNames.length) {
      for (VirtualFile child : children) {
        newNames.remove(child.getName());
      }
    }
    else {
      //noinspection SlowAbstractSetRemoveAll
      newNames.removeAll(persistedNames);
    }

    Set<String> deletedNames = new HashSet<>(persistedNames);
    ContainerUtil.removeAll(deletedNames, upToDateNames);

    ObjectOpenCustomHashSet<String> actualNames =
      dir.isCaseSensitive() ? null : (ObjectOpenCustomHashSet<String>)CollectionFactory.createFilePathSet(upToDateNames, false);
    if (LOG.isTraceEnabled()) {
      LOG.trace("current=" + persistedNames + " +" + newNames + " -" + deletedNames);
    }

    List<ChildInfo> newKids = new ArrayList<>(newNames.size());
    for (String newName : newNames) {
      checkCancelled(dir);
      ChildInfo record = childRecord(fs, dir, newName, false);
      if (record != null) {
        newKids.add(record);
      }
      else if (LOG.isTraceEnabled()) {
        LOG.trace("[+] fs=" + fs + " dir=" + dir + " name=" + newName);
      }
    }

    List<Pair<VirtualFile, FileAttributes>> updatedMap = new ArrayList<>(children.size() - deletedNames.size());
    List<VirtualFile> chs = ContainerUtil.filter(children, file -> !deletedNames.contains(file.getName()));

    if (fs instanceof BatchingFileSystem) {
      Set<String> names = ContainerUtil.map2Set(chs, file -> file.getName());
      Map<String, FileAttributes> map = ContainerUtil.filter(childrenWithAttributes, s -> names.contains(s));
      Map<String, VirtualFile> nameToFile = new HashMap<>();
      for (VirtualFile file : chs) {
        nameToFile.put(file.getName(), file);
      }
      for (Map.Entry<String, FileAttributes> e : map.entrySet()) {
        String name = e.getKey();
        FileAttributes attributes = e.getValue();
        updatedMap.add(Pair.create(nameToFile.get(name), attributes));
      }
    }
    else {
      t = System.nanoTime();
      for (VirtualFile child : chs) {
        checkCancelled(dir);
        updatedMap.add(new Pair<>(child, fs.getAttributes(child)));
      }
      myIoTime += System.nanoTime() - t;
    }

    if (isFullScanDirectoryChanged(dir, persistedNames, children)) {
      return false;
    }

    for (String name : deletedNames) {
      VirtualFileSystemEntry child = dir.findChild(name);
      if (child != null) {
        if (checkAndScheduleFileNameChange(actualNames, child)) {
          newKids.removeIf(newKidCandidate -> StringUtil.equalsIgnoreCase(newKidCandidate.getName(), child.getName()));
        }
        else {
          myHelper.scheduleDeletion(child);
        }
      }
    }

    for (ChildInfo record : newKids) {
      myHelper.scheduleCreation(dir, record.getName().toString(), record.getFileAttributes(), record.getSymlinkTarget(), () -> checkCancelled(dir));
    }

    for (Pair<VirtualFile, FileAttributes> pair : updatedMap) {
      NewVirtualFile child = (NewVirtualFile)pair.first;
      checkCancelled(child);
      FileAttributes childAttributes = pair.second;
      if (childAttributes != null) {
        checkAndScheduleChildRefresh(fs, persistence, dir, child, childAttributes);
        checkAndScheduleFileNameChange(actualNames, child);
      }
      else {
        if (LOG.isTraceEnabled()) LOG.trace("[x] fs=" + fs + " dir=" + dir + " name=" + child.getName());
        myHelper.scheduleDeletion(child);
      }
    }

    return !isFullScanDirectoryChanged(dir, persistedNames, children);
  }

  private boolean isFullScanDirectoryChanged(VirtualDirectoryImpl dir, List<String> names, List<VirtualFile> children) {
    var t = System.nanoTime();
    var changed = ReadAction.compute(() -> {
      checkCancelled(dir);
      return LocalFileSystemRefreshWorker.areChildrenOrNamesChanged(dir, names, children);
    });
    myVfsTime += System.nanoTime() - t;
    return changed;
  }

  private boolean partialDirRefresh(NewVirtualFileSystem fs, PersistentFS persistence, VirtualDirectoryImpl dir) {
    var t = System.nanoTime();
    Pair<List<VirtualFile>, List<String>> snapshot = ReadAction.compute(() -> {
      checkCancelled(dir);
      return new Pair<>(dir.getCachedChildren(), dir.getSuspiciousNames());
    });
    myVfsTime += System.nanoTime() - t;
    List<VirtualFile> cached = snapshot.getFirst();
    List<String> wanted = snapshot.getSecond();

    ObjectOpenCustomHashSet<String> actualNames;
    if (dir.isCaseSensitive() || cached.isEmpty()) {
      actualNames = null;
    }
    else {
      t = System.nanoTime();
      actualNames = (ObjectOpenCustomHashSet<String>)CollectionFactory.createFilePathSet(VfsUtil.filterNames(fs.list(dir)), false);
      myIoTime += System.nanoTime() - t;
    }

    if (LOG.isTraceEnabled()) {
      LOG.trace("cached=" + cached + " actual=" + actualNames + " suspicious=" + wanted);
    }

    List<Pair<VirtualFile, FileAttributes>> existingMap = new ArrayList<>(cached.size());
    t = System.nanoTime();
    for (VirtualFile child : cached) {
      checkCancelled(dir);
      existingMap.add(new Pair<>(child, fs.getAttributes(child)));
    }
    myIoTime += System.nanoTime() - t;

    List<ChildInfo> newKids = new ArrayList<>(wanted.size());
    for (String name : wanted) {
      if (name.isEmpty()) continue;
      checkCancelled(dir);
      ChildInfo record = childRecord(fs, dir, name, true);
      if (record != null) {
        newKids.add(record);
      }
    }

    if (isDirectoryChanged(dir, cached, wanted)) {
      return false;
    }

    for (Pair<VirtualFile, FileAttributes> pair : existingMap) {
      NewVirtualFile child = (NewVirtualFile)pair.first;
      checkCancelled(child);
      FileAttributes childAttributes = pair.second;
      if (childAttributes != null) {
        checkAndScheduleChildRefresh(fs, persistence, dir, child, childAttributes);
        checkAndScheduleFileNameChange(actualNames, child);
      }
      else {
        myHelper.scheduleDeletion(child);
      }
    }

    for (ChildInfo record : newKids) {
      myHelper.scheduleCreation(dir, record.getName().toString(), record.getFileAttributes(), record.getSymlinkTarget(), () -> checkCancelled(dir));
    }

    return !isDirectoryChanged(dir, cached, wanted);
  }

  private boolean isDirectoryChanged(VirtualDirectoryImpl dir, List<VirtualFile> cached, List<String> wanted) {
    var t = System.nanoTime();
    var changed = ReadAction.compute(() -> {
      checkCancelled(dir);
      return !cached.equals(dir.getCachedChildren()) || !wanted.equals(dir.getSuspiciousNames());
    });
    myVfsTime += System.nanoTime() - t;
    return changed;
  }

  private @Nullable ChildInfo childRecord(NewVirtualFileSystem fs, VirtualFile dir, String name, boolean canonicalize) {
    FakeVirtualFile file = new FakeVirtualFile(dir, name);
    var t = System.nanoTime();
    FileAttributes attributes = fs.getAttributes(file);
    if (attributes == null) {
      myIoTime += System.nanoTime() - t;
      return null;
    }
    boolean isEmptyDir = attributes.isDirectory() && !fs.hasChildren(file);
    String symlinkTarget = attributes.isSymLink() ? fs.resolveSymLink(file) : null;
    if (canonicalize) {
      name = fs.getCanonicallyCasedName(file);  // we need case-exact names in file events
    }
    myIoTime += System.nanoTime() - t;
    return new ChildInfoImpl(name, attributes, isEmptyDir ? ChildInfo.EMPTY_ARRAY : null, symlinkTarget);
  }

  static class RefreshCancelledException extends RuntimeException {
    @Override
    public synchronized Throwable fillInStackTrace() {
      return this;
    }
  }

  private void checkCancelled(NewVirtualFile stopAt) throws RefreshCancelledException {
    Consumer<? super VirtualFile> testListener = ourTestListener;
    if (testListener != null) {
      testListener.accept(stopAt);
    }
    if (myCancelled) {
      if (LOG.isTraceEnabled()) LOG.trace("cancelled at: " + stopAt);
      forceMarkDirty(stopAt);
      NewVirtualFile file;
      while ((file = myRefreshQueue.pollFirst()) != null) {
        forceMarkDirty(file);
      }
      throw new RefreshCancelledException();
    }
  }

  private static void forceMarkDirty(NewVirtualFile file) {
    file.markClean();  // otherwise, consequent markDirty() won't have any effect
    file.markDirty();
  }

  private void checkAndScheduleChildRefresh(NewVirtualFileSystem fs,
                                            PersistentFS persistence,
                                            @Nullable NewVirtualFile parent,
                                            NewVirtualFile child,
                                            FileAttributes childAttributes) {
    if (!VfsEventGenerationHelper.checkDirty(child)) {
      return;
    }

    if (checkAndScheduleFileTypeChange(fs, parent, child, childAttributes)) {
      child.markClean();
      return;
    }

    myHelper.checkWritableAttributeChange(child, persistence.isWritable(child), childAttributes.isWritable());

    if (SystemInfo.isWindows) {
      myHelper.checkHiddenAttributeChange(child, child.is(VFileProperty.HIDDEN), childAttributes.isHidden());
    }

    if (childAttributes.isSymLink()) {
      var t = System.nanoTime();
      var target = fs.resolveSymLink(child);
      myIoTime += System.nanoTime() - t;
      myHelper.checkSymbolicLinkChange(child, child.getCanonicalPath(), target);
    }

    if (!childAttributes.isDirectory()) {
      long oltTS = persistence.getTimeStamp(child), newTS = childAttributes.lastModified;
      long oldLength = persistence.getLastRecordedLength(child), newLength = childAttributes.length;
      myHelper.checkContentChanged(child, oltTS, newTS, oldLength, newLength);
      child.markClean();
    }
    else if (myIsRecursive) {
      queueDirectory(child);
    }
  }

  private boolean checkAndScheduleFileTypeChange(NewVirtualFileSystem fs,
                                                 @Nullable NewVirtualFile parent,
                                                 NewVirtualFile child,
                                                 FileAttributes childAttributes) {
    boolean currentIsDirectory = child.isDirectory(), upToDateIsDirectory = childAttributes.isDirectory();
    boolean currentIsSymlink = child.is(VFileProperty.SYMLINK), upToDateIsSymlink = childAttributes.isSymLink();
    boolean currentIsSpecial = child.is(VFileProperty.SPECIAL), upToDateIsSpecial = childAttributes.isSpecial();

    boolean isFileTypeChanged = currentIsSymlink != upToDateIsSymlink || currentIsSpecial != upToDateIsSpecial;
    if (currentIsDirectory != upToDateIsDirectory ||
        (isFileTypeChanged && !Boolean.getBoolean("refresh.ignore.file.type.changes"))) {
      myHelper.scheduleDeletion(child);
      if (parent != null) {
        var t = System.nanoTime();
        String symlinkTarget = upToDateIsSymlink ? fs.resolveSymLink(child) : null;
        myIoTime += System.nanoTime() - t;
        myHelper.scheduleCreation(parent, child.getName(), childAttributes, symlinkTarget, () -> checkCancelled(parent));
      }
      else {
        LOG.error("transgender orphan: " + child + ' ' + childAttributes);
      }
      return true;
    }

    return false;
  }

  // true if the event was scheduled
  private boolean checkAndScheduleFileNameChange(@Nullable ObjectOpenCustomHashSet<String> actualNames, VirtualFile child) {
    if (actualNames != null) {
      String currentName = child.getName();
      String actualName = actualNames.get(currentName);
      if (actualName != null && !currentName.equals(actualName)) {
        myHelper.scheduleAttributeChange(child, VirtualFile.PROP_NAME, currentName, actualName);
        return true;
      }
    }
    return false;
  }

  static Consumer<? super VirtualFile> ourTestListener;
}
