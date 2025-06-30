package com.example.zura

import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

/**
 * This activity handles managing user profile information.
 *
 * This activity allows users to:
 * - View and update their full name
 * - Change their password
 * - Delete their account
 *
 * All changes require confirmation before being applied.
 */
class ManageProfileActivity : AppCompatActivity() {

    // UI Components
    private lateinit var nameField: EditText
    private lateinit var passwordField: EditText
    private lateinit var confirmField: EditText
    private lateinit var saveButton: Button
    private lateinit var deleteButton: Button

    // Firebase instances
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    /**
     * Called when the activity is first created.
     * Initializes UI components and sets up click listeners.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_manage_profile)

        // Initialize UI components
        nameField = findViewById(R.id.edit_name)
        passwordField = findViewById(R.id.edit_password)
        confirmField = findViewById(R.id.edit_confirm)
        saveButton = findViewById(R.id.save_button)
        deleteButton = findViewById(R.id.delete_button)

        // Set hint for name field
        nameField.hint = "Change Full Name"

        // Load current user data
        loadUserData()

        // Set up save button click listener
        saveButton.setOnClickListener {
            val newName = nameField.text.toString().trim()
            val newPassword = passwordField.text.toString()
            val confirmPassword = confirmField.text.toString()

            // Validate inputs
            if (newName.isEmpty()) {
                Toast.makeText(this, "Full name cannot be empty.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (newPassword.isNotEmpty() && newPassword != confirmPassword) {
                Toast.makeText(this, "Passwords do not match.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            showSaveConfirmation(newName, newPassword)
        }

        // Set up delete button click listener
        deleteButton.setOnClickListener {
            showDeleteConfirmation()
        }
    }

    /**
     * Loads the current user's data from Firestore.
     * Populates the name field with the user's current full name.
     */
    private fun loadUserData() {
        val user = auth.currentUser
        user?.let {
            db.collection("users").document(it.uid).get()
                .addOnSuccessListener { doc ->
                    nameField.setText(doc.getString("fullName") ?: "")
                }
        }
    }

    /**
     * Shows a confirmation dialog for account deletion.
     */
    private fun showDeleteConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Delete Account")
            .setMessage("Are you sure you want to delete your Zura account?")
            .setNegativeButton("Yes") { _, _ -> deleteAccount() }
            .setPositiveButton("No", null)
            .show()
    }

    /**
     * Shows a confirmation dialog for profile changes.
     *
     * @param newName The new full name to be saved
     * @param newPassword The new password to be set (empty if not changing)
     */
    private fun showSaveConfirmation(newName: String, newPassword: String) {
        AlertDialog.Builder(this)
            .setTitle("Confirm Changes")
            .setMessage("Are you sure you want to change your profile details?")
            .setNegativeButton("Yes") { _, _ -> updateAccountDetails(newName, newPassword) }
            .setPositiveButton("No", null)
            .show()
    }

    /**
     * Updates the user's account details in Firebase.
     *
     * @param newName The new full name to save
     * @param newPassword The new password to set (only updated if not empty)
     */
    private fun updateAccountDetails(newName: String, newPassword: String) {
        val user = auth.currentUser
        user?.let {
            // Update name in Firestore
            db.collection("users").document(user.uid)
                .update("fullName", newName)

            // Update password if provided
            if (newPassword.isNotEmpty()) {
                user.updatePassword(newPassword)
            }

            Toast.makeText(this, "Profile updated.", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    /**
     * Deletes the user's account from Firebase Authentication and Firestore.
     * Navigates back to login screen upon successful deletion.
     */
    private fun deleteAccount() {
        val user = auth.currentUser
        val uid = user?.uid

        if (user != null && uid != null) {
            // Delete user data from Firestore first
            db.collection("users").document(uid).delete()
                .addOnSuccessListener {
                    // Then delete authentication account
                    user.delete()
                        .addOnSuccessListener {
                            Toast.makeText(this, "Account deleted.", Toast.LENGTH_SHORT).show()
                            startActivity(Intent(this, LoginActivity::class.java))
                            finish()
                        }
                        .addOnFailureListener { e ->
                            Toast.makeText(this,
                                "Failed to delete account: ${e.message}",
                                Toast.LENGTH_LONG).show()
                        }
                }
                .addOnFailureListener { e ->
                    Toast.makeText(this,
                        "Failed to delete user data: ${e.message}",
                        Toast.LENGTH_LONG).show()
                }
        }
    }
}