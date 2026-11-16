package com.strmr.tv.api

/** A rejected operation is not a missing source, an old server or permission to try another route. */
enum class VodAccessDenial(val message:String) {
    PROFILE_REQUIRED("Select a profile before playing this title."),
    BLOCKED("This title is not available for this profile."),
    CLASSIFICATION_UNKNOWN("This title's age classification could not be confirmed for this profile."),
    PARENT_APPROVAL_REQUIRED("A parent must approve this title before it can be played."),
    APPROVAL_EXPIRED("The approval for this title has expired. Ask a parent to review it."),
    CONTEXT_CHANGED("Access to this title changed. Reopen it in the current profile."),
    UNSUPPORTED_SOURCE("This source cannot be authorized for this profile."),
    PROFILE_LOCKED("Unlock this profile before continuing."),
    AUTHENTICATION("Sign in again before continuing."),
    ACCESS_DENIED("This profile cannot perform this action."),
}

/** Fixed server codes are additive; they do not activate an age policy or interpret rating metadata. */
fun vodAccessDenial(status:Int?,code:String?):VodAccessDenial? = when(code) {
    "kids_vod_profile_required"->VodAccessDenial.PROFILE_REQUIRED
    "kids_vod_access_denied"->VodAccessDenial.BLOCKED
    "kids_vod_classification_unknown"->VodAccessDenial.CLASSIFICATION_UNKNOWN
    "kids_vod_parent_approval_required"->VodAccessDenial.PARENT_APPROVAL_REQUIRED
    "kids_vod_approval_expired"->VodAccessDenial.APPROVAL_EXPIRED
    "kids_vod_context_changed"->VodAccessDenial.CONTEXT_CHANGED
    "kids_vod_unsupported_source"->VodAccessDenial.UNSUPPORTED_SOURCE
    ApiError.Code.PIN_INVALID,ApiError.Code.PIN_REQUIRED,ApiError.Code.PROFILE_LOCKED->VodAccessDenial.PROFILE_LOCKED
    else->when(status) {401->VodAccessDenial.AUTHENTICATION;403->VodAccessDenial.ACCESS_DENIED;else->null}
}

val Throwable.vodDenial:VodAccessDenial?
    get() = when(this) {
        is ApiError.AccessDenied->denial
        is ApiError.SignedOut->VodAccessDenial.AUTHENTICATION
        is ApiError->vodAccessDenial(statusCode,code)
        else->null
    }

/** Call only for an optional read or source fallback; cancellation always keeps its original meaning. */
fun Throwable.rethrowVodDenial() {
    if(this is kotlinx.coroutines.CancellationException || vodDenial!=null)throw this
}
