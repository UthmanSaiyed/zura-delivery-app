package com.example.zura

import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Patterns
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.zura.databinding.ActivityLoginBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

/**
 * This class is about handling user authentication and login functionality.
 *
 * This activity provides:
 * - Email/password login using Firebase Authentication
 * - Role-based navigation (driver vs regular user)
 * - Password reset functionality
 * - Signup redirection
 *
 * The activity validates input fields and handles authentication errors.
 */
class LoginActivity : AppCompatActivity() {

    /** View binding instance for the login activity layout */
    private lateinit var binding: ActivityLoginBinding

    /** Firebase Authentication instance */
    private lateinit var firebaseAuth: FirebaseAuth

    /**
     * Called when the activity is first created.
     * Sets up the UI components and click listeners.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize Firebase Auth
        firebaseAuth = FirebaseAuth.getInstance()

        // Set up login button click listener
        binding.loginButton.setOnClickListener {
            val email = binding.loginEmail.text.toString()
            val password = binding.loginPassword.text.toString()

            if (email.isNotEmpty() && password.isNotEmpty()) {
                // Attempt to sign in with provided credentials
                firebaseAuth.signInWithEmailAndPassword(email, password).addOnCompleteListener {
                    if (it.isSuccessful) {
                        val uid = firebaseAuth.currentUser?.uid
                        if (uid != null) {
                            // Check user role in Firestore
                            val db = FirebaseFirestore.getInstance()
                            val userRef = db.collection("users").document(uid)

                            userRef.get().addOnSuccessListener { document ->
                                if (document != null && document.exists()) {
                                    val isDriver = document.getBoolean("isDriver") ?: false

                                    // Navigate to appropriate activity based on role
                                    when {
                                        isDriver -> {
                                            startActivity(Intent(this, DriverActivity::class.java))
                                        }
                                        else -> {
                                            startActivity(Intent(this, MainActivity::class.java))
                                        }
                                    }

                                    finish()
                                } else {
                                    Toast.makeText(this,
                                        "User record not found in Firestore.",
                                        Toast.LENGTH_SHORT).show()
                                }
                            }.addOnFailureListener { exception ->
                                Toast.makeText(this,
                                    "Failed to check user role: ${exception.message}",
                                    Toast.LENGTH_LONG).show()
                            }
                        }
                    } else {
                        Toast.makeText(this,
                            it.exception?.message ?: "Login failed",
                            Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                Toast.makeText(this,
                    "Fields cannot be empty",
                    Toast.LENGTH_SHORT).show()
            }
        }

        // Set up forgot password click listener
        binding.forgotPassword.setOnClickListener {
            showForgotPasswordDialog()
        }

        // Set up signup redirect click listener
        binding.signupRedirectText.setOnClickListener {
            val signupIntent = Intent(this, SignupActivity::class.java)
            startActivity(signupIntent)
        }
    }

    /**
     * Displays the forgot password dialog with email input field.
     */
    private fun showForgotPasswordDialog() {
        val builder = AlertDialog.Builder(this)
        val view = layoutInflater.inflate(R.layout.dialog_forgot, null)
        val userEmail = view.findViewById<EditText>(R.id.editBox)

        builder.setView(view)
        val dialog = builder.create()

        // Make dialog cancelable
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)

        // Set up reset button click listener
        view.findViewById<Button>(R.id.btnReset).setOnClickListener {
            compareEmail(userEmail)
            dialog.dismiss()
        }

        // Set up cancel button click listener
        view.findViewById<Button>(R.id.btnCancel).setOnClickListener {
            dialog.dismiss()
        }

        // Make dialog background transparent
        dialog.window?.setBackgroundDrawable(ColorDrawable(0))
        dialog.show()
    }

    /**
     * Validates an email and sends password reset instructions if valid.
     *
     * @param email EditText containing the email address to validate
     */
    private fun compareEmail(email: EditText) {
        val emailText = email.text.toString()
        if (emailText.isEmpty() || !Patterns.EMAIL_ADDRESS.matcher(emailText).matches()) {
            return
        }
        // Send password reset email
        firebaseAuth.sendPasswordResetEmail(emailText).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                Toast.makeText(this,
                    "Check your email",
                    Toast.LENGTH_SHORT).show()
            }
        }
    }
}