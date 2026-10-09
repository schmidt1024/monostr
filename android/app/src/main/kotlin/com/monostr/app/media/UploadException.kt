package com.monostr.app.media

/**
 * Why an upload or a delete failed, as far as the app tells the cases apart (spec 5.1). The
 * messages are for logs; the UI maps the type to a fixed text (`uploadMessage()`).
 */
sealed class UploadException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** The server refuses adult content. */
    class Nsfw : UploadException("refused: adult content")
    /** Larger than the server takes, or still above 10 MB after the preparation on the device. */
    class TooLarge : UploadException("refused: too large")
    /** The account's storage on the server is full. */
    class Quota : UploadException("refused: quota full")
    /** A daily limit is reached. */
    class Rate : UploadException("refused: rate limit")
    /** Not a kind of file the server takes. */
    class Type : UploadException("refused: type")
    /** The account or this very file is banned. */
    class Banned : UploadException("refused: banned")
    /** The server did not accept the authorization. */
    class Auth : UploadException("refused: authorization")
    /** The server, or a part of it, is down for the moment. */
    class Unavailable : UploadException("server unavailable")
    /** No answer at all. */
    class Network(cause: Throwable) : UploadException("network", cause)
    /** Anything else: an unknown status, or an answer that does not describe the uploaded bytes. */
    class Rejected(val status: Int) : UploadException("refused: $status")
    /** The picture could not be read or prepared on the device. */
    class Unreadable(cause: Throwable? = null) : UploadException("unreadable picture", cause)
}
