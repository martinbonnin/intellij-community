package org.jetbrains.idea.svn.history;

import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.RepositoryLocation;
import com.intellij.openapi.vcs.actions.DirectoryDetector;
import org.jetbrains.annotations.Nullable;

/**
 * @author yole
 */
public class SvnRepositoryLocation implements RepositoryLocation {
  private FilePath myRootFile;
  private String myURL;

  public SvnRepositoryLocation(final FilePath rootFile, final String URL) {
    myRootFile = rootFile;
    myURL = URL;
  }

  public SvnRepositoryLocation(final String URL) {
    myURL = URL;
  }

  public String toString() {
    return myURL;
  }

  public String toPresentableString() {
    return myURL;
  }

  public FilePath getRootFile() {
    return myRootFile;
  }

  public String getURL() {
    return myURL;
  }

  @Nullable
  protected FilePath detectWhenNoRoot(final String fullPath, final DirectoryDetector detector) {
    return null;
  }

  @Nullable
  public FilePath getLocalPath(final String fullPath, final DirectoryDetector detector) {
    if (myRootFile == null) {
      return detectWhenNoRoot(fullPath, detector);
    }

    if (fullPath.startsWith(myURL)) {
      return LocationDetector.filePathByUrlAndPath(fullPath, myURL, myRootFile.getPresentableUrl(), detector);
    }
    return null;
  }
}
