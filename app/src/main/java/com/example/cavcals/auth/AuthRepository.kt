package com.example.cavcals.auth

import com.google.firebase.auth.FirebaseAuth
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

    // Copy only the fields the app needs out of Firebase's user object.
    private fun FirebaseAuth.toSignedInUser(): SignedInUser? =
        currentUser?.let { user -> SignedInUser(uid = user.uid, email = user.email) }
}
