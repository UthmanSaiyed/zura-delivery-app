package com.example.zura

import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

/**
 * This class displays requesting a new delivery service.
 *
 * This activity allows users to:
 * - Select a store from available options
 * - Enter their receipt number
 * - Add delivery notes
 * - Submit the delivery request
 *
 * Validates all inputs before proceeding to confirmation.
 */
class RequestDeliveryActivity : AppCompatActivity() {

    // UI Components
    private lateinit var receiptNumberInput: EditText
    private lateinit var storeSpinner: Spinner
    private lateinit var noteInput: EditText
    private lateinit var submitButton: Button
    private lateinit var infoIcon: ImageView

    // Firebase instances
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    /**
     * Called when the activity is first created.
     * Initializes UI components and sets up event listeners.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_request_delivery)

        // Initialize views
        receiptNumberInput = findViewById(R.id.receiptNumberInput)
        storeSpinner = findViewById(R.id.storeSpinner)
        noteInput = findViewById(R.id.noteInput)
        submitButton = findViewById(R.id.submitRequestBtn)
        infoIcon = findViewById(R.id.infoIcon)

        // Set text color for better visibility
        receiptNumberInput.setTextColor(resources.getColor(android.R.color.black))
        noteInput.setTextColor(resources.getColor(android.R.color.black))

        // Set up info icon click listener
        setupInfoIcon()

        // Load available stores from Firebase
        loadStoresFromFirebase()

        // Set up submit button click listener
        setupSubmitButton()
    }

    /**
     * Configures the info icon to show help dialog when clicked.
     */
    private fun setupInfoIcon() {
        infoIcon.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Receipt Number Help")
                .setMessage("Please enter your 10-digit receipt number from your existing order. " +
                        "This helps the driver identify and collect your items from the store.")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    /**
     * Loads available stores from Firestore and populates the spinner.
     */
    private fun loadStoresFromFirebase() {
        db.collection("stores").get()
            .addOnSuccessListener { result ->
                val storeNames = result.mapNotNull { it.getString("name") }

                if (storeNames.isNotEmpty()) {
                    // Create and set adapter for spinner
                    val adapter = ArrayAdapter(
                        this,
                        android.R.layout.simple_spinner_dropdown_item,
                        storeNames
                    )
                    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                    storeSpinner.adapter = adapter

                    // Set text color for selected item
                    storeSpinner.post {
                        (storeSpinner.selectedView as? TextView)?.setTextColor(
                            resources.getColor(android.R.color.white)
                        )
                    }

                    // Set item selection listener to maintain text color
                    storeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(
                            parent: AdapterView<*>,
                            view: android.view.View,
                            position: Int,
                            id: Long
                        ) {
                            (view as? TextView)?.setTextColor(
                                resources.getColor(android.R.color.white)
                            )
                        }

                        override fun onNothingSelected(parent: AdapterView<*>) {}
                    }
                } else {
                    showToast("No stores found on system.")
                }
            }
            .addOnFailureListener {
                showToast("Failed to load stores: ${it.message}")
            }
    }

    /**
     * Configures the submit button with validation and navigation logic.
     */
    private fun setupSubmitButton() {
        submitButton.setOnClickListener {
            val receiptNumber = receiptNumberInput.text.toString().trim()
            val store = storeSpinner.selectedItem?.toString() ?: ""
            val note = noteInput.text.toString().trim()

            // Validate inputs
            if (!validateInputs(receiptNumber, note)) {
                return@setOnClickListener
            }

            // Proceed to confirmation activity
            val intent = Intent(this, ConfirmOrderActivity::class.java).apply {
                putExtra("store", store)
                putExtra("receiptNumber", receiptNumber)
                putExtra("note", note)
            }

            startActivity(intent)
        }
    }

    /**
     * Validates user inputs before submission.
     *
     * @param receiptNumber The receipt number to validate
     * @param note The delivery notes to validate
     * @return true if all inputs are valid, false otherwise
     */
    private fun validateInputs(receiptNumber: String, note: String): Boolean {
        if (receiptNumber.isEmpty()) {
            showToast("Receipt number cannot be empty.")
            return false
        }

        if (!receiptNumber.matches(Regex("^\\d{10}$"))) {
            showToast("Receipt number must be exactly 10 number digits.")
            return false
        }

        if (note.isEmpty()) {
            showToast("Delivery notes cannot be empty.")
            return false
        }

        return true
    }

    /**
     * Shows a short toast message.
     *
     * @param message The message to display
     */
    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}