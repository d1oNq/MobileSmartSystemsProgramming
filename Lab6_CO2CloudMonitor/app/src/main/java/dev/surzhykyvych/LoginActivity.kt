package dev.surzhykyvych

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.auth.FirebaseAuth

class LoginActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth

    private lateinit var layoutEmail: TextInputLayout
    private lateinit var layoutPassword: TextInputLayout
    private lateinit var etEmail: TextInputEditText
    private lateinit var etPassword: TextInputEditText
    private lateinit var btnLogin: Button
    private lateinit var btnRegister: Button
    private lateinit var progressLoading: CircularProgressIndicator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        auth = FirebaseAuth.getInstance()

        if (auth.currentUser != null) {
            goToMainActivity()
            return
        }

        initUI()
        setupListeners()
    }

    private fun initUI() {
        layoutEmail = findViewById(R.id.layoutEmail)
        layoutPassword = findViewById(R.id.layoutPassword)
        etEmail = findViewById(R.id.etEmail)
        etPassword = findViewById(R.id.etPassword)
        btnLogin = findViewById(R.id.btnLogin)
        btnRegister = findViewById(R.id.btnRegister)
        progressLoading = findViewById(R.id.progressLoading)
    }

    private fun setupListeners() {
        btnLogin.setOnClickListener {
            hideKeyboard()
            if (validateInputs(isLogin = true)) {
                performLogin()
            }
        }

        btnRegister.setOnClickListener {
            hideKeyboard()
            if (validateInputs(isLogin = false)) {
                performRegistration()
            }
        }
    }

    private fun performLogin() {
        val email = etEmail.text.toString().trim()
        val password = etPassword.text.toString().trim()

        setLoadingState(true)

        auth.signInWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                setLoadingState(false)
                if (task.isSuccessful) {
                    goToMainActivity()
                } else {
                    // Показуємо помилку під полем пароля (бо найчастіше це неправильний пароль)
                    layoutPassword.error = "Невірний email або пароль"
                }
            }
    }

    private fun performRegistration() {
        val email = etEmail.text.toString().trim()
        val password = etPassword.text.toString().trim()

        setLoadingState(true)

        auth.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                setLoadingState(false)
                if (task.isSuccessful) {
                    Toast.makeText(
                        this,
                        getString(R.string.toast_register_success),
                        Toast.LENGTH_SHORT
                    ).show()
                    goToMainActivity()
                } else {
                    layoutEmail.error = task.exception?.localizedMessage ?: "Помилка реєстрації"
                }
            }
    }

    private fun validateInputs(isLogin: Boolean): Boolean {
        var isValid = true
        val email = etEmail.text.toString().trim()
        val password = etPassword.text.toString().trim()

        layoutEmail.error = null
        layoutPassword.error = null

        if (email.isEmpty()) {
            layoutEmail.error = getString(R.string.error_empty_field)
            isValid = false
        } else if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            layoutEmail.error = getString(R.string.error_invalid_email)
            isValid = false
        }

        if (password.isEmpty()) {
            layoutPassword.error = getString(R.string.error_empty_field)
            isValid = false
        } else if (!isLogin && password.length < 6) {
            layoutPassword.error = getString(R.string.error_short_password)
            isValid = false
        }

        return isValid
    }

    private fun setLoadingState(isLoading: Boolean) {
        if (isLoading) {
            progressLoading.visibility = View.VISIBLE
            btnLogin.visibility = View.GONE
            btnRegister.isEnabled = false
            etEmail.isEnabled = false
            etPassword.isEnabled = false
        } else {
            progressLoading.visibility = View.GONE
            btnLogin.visibility = View.VISIBLE
            btnRegister.isEnabled = true
            etEmail.isEnabled = true
            etPassword.isEnabled = true
        }
    }

    private fun hideKeyboard() {
        val view = this.currentFocus
        if (view != null) {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }

    private fun goToMainActivity() {
        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
        finish()
    }
}