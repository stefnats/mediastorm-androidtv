package com.strmr.tv.player

import com.strmr.tv.api.DetailsTarget
import org.junit.Assert.*
import org.junit.Test

class LocalFileAccessTest {
    private fun session()=PlayerSession(PlaybackRequest(DetailsTarget("tmdb:movie:1","Synthetic",true)),"file:///synthetic/file.mkv",null,localFile=true)
    @Test fun `unowned legacy local session and stale notification cannot enter player`() {assertFalse(session().playbackAllowed())}
    @Test fun `captured local ownership cannot survive profile or account A B A epoch`() {
        var context=1;val captured=context;val session=session().copy(localFileAccess={context==captured})
        assertTrue(session.playbackAllowed());context=2;assertFalse(session.playbackAllowed());context=3;assertFalse(session.playbackAllowed())
    }
    @Test fun `unknown cold or restricted context immediately closes local access without changing file`() {
        var permission=true;val session=session().copy(localFileAccess={permission});assertTrue(session.playbackAllowed());permission=false;assertFalse(session.playbackAllowed())
        assertFalse(session.copy(localFileAccess={error("Synthetic unavailable context")}).playbackAllowed())
    }
    @Test fun `existing online sessions do not require offline permission`() {assertTrue(session().copy(localFile=false).playbackAllowed())}
}
