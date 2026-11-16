package com.strmr.mobile.downloads

import com.strmr.mobile.connection.RouteRules
import com.strmr.tv.api.ApiClient
import com.strmr.tv.app.AppSession
import com.strmr.tv.app.NetworkRoutes
import com.strmr.tv.library.ProfileDataContext
import com.strmr.tv.remote.RemoteAccess
import kotlinx.coroutines.CancellationException

class DownloadAccessError(message:String=OfflineAccess.CONTEXT_REQUIRED):Exception(message)

/** A current adult context, not a persisted offline authorization or an age-evidence grant. */
@ConsistentCopyVisibility
data class OfflineContext internal constructor(val owner:DownloadOwner.Known,val epoch:Long)

object OfflineAccess {
    const val CONTEXT_REQUIRED="Downloads require their original server, account and profile. Sign in and select that profile to continue."
    const val RESTRICTED="Offline downloads are unavailable for this profile without a current verified permission."
    fun current():OfflineContext? {
        val epoch=ProfileDataContext.current
        return try {ApiClient.inContext(epoch) {
            val profile=AppSession.profile.value
            val account=ApiClient.session?.accountId
            if(AppSession.phase.value!=AppSession.Phase.Ready || AppSession.pinChallenge.value!=null || profile==null || profile.id!=ApiClient.profileId || !profile.offlineAdultVerified || account.isNullOrEmpty())return@inContext null
            val configured=NetworkRoutes.savedServerUrl
            val pairing=RemoteAccess.pairing
            val throughPairedTunnel=AppSession.isRemote.value && pairing!=null && ApiClient.remoteCredential==pairing.credential
            val throughVerifiedHome=NetworkRoutes.isHomeDirect.value && configured==null && pairing!=null
            val paired=if(throughPairedTunnel || throughVerifiedHome)pairing?.invite?.let(RouteRules::serverId)else null
            val key=DownloadServerKey.paired(paired) ?: DownloadServerKey.configured(configured) ?: return@inContext null
            val owner=DownloadOwner.Known(key,account,profile.id).takeIf {it.valid()} ?: return@inContext null
            offlineContext(owner,epoch,ready=true,unlocked=true,adultVerified=profile.offlineAdultVerified,selectedProfileMatches=profile.id==ApiClient.profileId)
        }} catch(_:CancellationException) {null}
    }
    fun capture():OfflineContext=current() ?: throw DownloadAccessError(unavailableMessage())
    fun unavailableMessage():String=if(AppSession.profile.value?.isKidsProfile==true)RESTRICTED else CONTEXT_REQUIRED
    fun allows(item:DownloadItem,context:OfflineContext?=current()):Boolean =
        context!=null && item.owner is DownloadOwner.Known && item.owner.valid() && item.owner==context.owner && context.epoch==ProfileDataContext.current
    fun <T> use(context:OfflineContext,item:DownloadItem?=null,action:()->T):T=ApiClient.inContext(context.epoch) {require(context,item);action()}
    fun require(context:OfflineContext,item:DownloadItem?=null) {
        ProfileDataContext.requireCurrent(context.epoch)
        if(current()!=context || item!=null && !allows(item,context))throw DownloadAccessError()
    }
}

/** All state predicates are required; a persisted profile flag never substitutes for raw-reply proof. */
internal fun offlineContext(owner:DownloadOwner.Known?,epoch:Long,ready:Boolean,unlocked:Boolean,adultVerified:Boolean,selectedProfileMatches:Boolean):OfflineContext? =
    owner?.takeIf {ready && unlocked && adultVerified && selectedProfileMatches && it.valid()}?.let {OfflineContext(it,epoch)}
