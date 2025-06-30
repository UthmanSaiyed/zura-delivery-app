package com.example.zura

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.zura.databinding.ActivitySignupBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlin.random.Random

/**
 * This class displays new user registration.
 *
 * This activity handles:
 * - User account creation with email/password
 * - User profile data storage in Firestore
 * - Automatic generation of delivery PIN
 * - Input validation and error handling
 * - Navigation to login screen after successful registration
 */
class SignupActivity : AppCompatActivity() {

    /** View binding instance */
    private lateinit var binding: ActivitySignupBinding

    /** Firebase Authentication instance */
    private lateinit var firebaseAuth: FirebaseAuth

    /** Firestore database instance */
    private lateinit var firestore: FirebaseFirestore

    /**
     * Called when the activity is first created.
     * Sets up UI components and click listeners.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySignupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize Firebase services
        firebaseAuth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()

        // Set up signup button click listener
        binding.signupButton.setOnClickListener {
            handleSignup()
        }

        // Set up login redirect click listener
        binding.loginRedirectText.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
        }
    }

    /**
     * Handles the signup process including validation and account creation.
     */
    private fun handleSignup() {
        // Get input values
        val fullName = binding.signupFullname.text.toString().trim()
        val email = binding.signupEmail.text.toString().trim()
        val password = binding.signupPassword.text.toString().trim()
        val confirmPassword = binding.signupConfirm.text.toString().trim()

        // Validate inputs
        if (!validateInputs(fullName, email, password, confirmPassword)) {
            return
        }

        // Create Firebase user account
        firebaseAuth.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    // Save additional user data to Firestore
                    saveUserData(fullName, email)
                } else {
                    Toast.makeText(
                        this,
                        task.exception?.message ?: "Signup failed",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
    }

    /**
     * Validates user input fields.
     *
     * @param fullName User's full name
     * @param email User's email address
     * @param password User's password
     * @param confirmPassword Password confirmation
     * @return true if all inputs are valid, false otherwise
     */
    private fun validateInputs(
        fullName: String,
        email: String,
        password: String,
        confirmPassword: String
    ): Boolean {
        if (fullName.isEmpty() || email.isEmpty() || password.isEmpty() || confirmPassword.isEmpty()) {
            Toast.makeText(this, "All fields are required", Toast.LENGTH_SHORT).show()
            return false
        }

        if (password != confirmPassword) {
            Toast.makeText(this, "Passwords do not match", Toast.LENGTH_SHORT).show()
            return false
        }

        return true
    }

    /**
     * Saves user data to Firestore after successful authentication.
     *
     * @param fullName User's full name
     * @param email User's email address
     */
    private fun saveUserData(fullName: String, email: String) {
        val uid = firebaseAuth.currentUser?.uid ?: return

        // Generate a random 4-digit PIN (1000–9999)
        val deliveryPin = Random.nextInt(1000, 9999).toString()

        // Create user data map
        val userMap = hashMapOf(
            "fullName" to fullName,
            "email" to email,
            "isNormalUser" to true,
            "isDriver" to false,
            "deliveryPin" to deliveryPin
        )

        // Save to Firestore
        firestore.collection("users").document(uid)
            .set(userMap)
            .addOnSuccessListener {
                Toast.makeText(this, "Signup successful!", Toast.LENGTH_LONG).show()
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
            }
            .addOnFailureListener { e ->
                Toast.makeText(
                    this,
                    "Failed to save user info: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
    }
}