package com.example.cavcals.auth

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

/**
 * The signed-in account as the rest of the app sees it. Screens use this small app-owned type
 * instead of Firebase's FirebaseUser, so Firebase classes stay inside this repository.
 */
data class SignedInUser(
    val uid: String,
    val email: String?,
)

/**
 * The single place in the app that talks to Firebase Authentication.
 *
 * Firebase owns the login session: it checks the password, stores the signed-in user on the
 * device, and restores that user on the next launch. The rest of the app only reads
 * [currentUser] and never keeps its own "logged in" flag, so the screen shown always matches
 * what Firebase knows.
 *
 * This is an `object` so the whole app shares one repository and one session listener.
 */
object AuthRepository {

    private val firebaseAuth: FirebaseAuth = FirebaseAuth.getInstance()

    // Start with the user Firebase already restored from disk. Reading it synchronously here
    // means a relaunch with a saved session goes straight to the signed-in screen instead of
    // flashing the login form first.
    private val _currentUser = MutableStateFlow(firebaseAuth.toSignedInUser())

    /** The signed-in user, or null when signed out. Firebase is the source of truth. */
    val currentUser: StateFlow<SignedInUser?> = _currentUser.asStateFlow()

    init {
        // Firebase calls this listener whenever the session changes (sign-in, sign-out, or the
        // session becoming invalid). Copying the user into the StateFlow lets Compose react to it.
        firebaseAuth.addAuthStateListener { auth ->
            _currentUser.value = auth.toSignedInUser()
        }
    }

    /**
     * Signs in with an existing email/password account.
     *
     * Firebase returns a Play Services `Task`, which normally needs callbacks. `await()` (from
     * kotlinx-coroutines-play-services) suspends the coroutine until the Task finishes instead,
     * so the caller can write ordinary top-to-bottom code with try/catch. A failed sign-in
     * throws the Firebase exception, and the ViewModel turns it into a friendly message.
     *
     * Nothing is returned on success: the auth state listener above publishes the new user,
     * and that is what moves the app to the signed-in screen.
     */
    suspend fun signIn(email: String, password: String) {
        firebaseAuth.signInWithEmailAndPassword(email, password).await()
    }

    /**
     * Creates a new email/password account. Firebase signs the new user in right away, so the
     * auth state listener publishes them and the app moves to the signed-in screen on its own,
     * exactly like [signIn]. Failures (email already used, weak password, no network) are thrown
     * as Firebase exceptions for the ViewModel to translate.
     */
    suspend fun createAccount(email: String, password: String) {
        firebaseAuth.createUserWithEmailAndPassword(email, password).await()
    }

    /**
     * Signs in with a Google account.
     *
     * The Login screen asks Google (through Credential Manager) for an ID token, which is Google's
     * signed proof of "this person owns this Google account." Firebase checks that proof and
     * creates or reuses the matching Firebase user. As with email sign-in, nothing is returned:
     * the auth state listener publishes the new user and the app switches screens on its own.
     */
    suspend fun signInWithGoogle(idToken: String) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        firebaseAuth.signInWithCredential(credential).await()
    }

    /**
     * Signs out. Like sign-in, nothing navigates here: the auth state listener publishes null,
     * and MainActivity swaps the signed-in app for the Login screen on its own.
     */
    fun signOut() {
        firebaseAuth.signOut()
    }

    // Copy only the fields the app needs out of Firebase's user object.
    private fun FirebaseAuth.toSignedInUser(): SignedInUser? =
        currentUser?.let { user -> SignedInUser(uid = user.uid, email = user.email) }
}
