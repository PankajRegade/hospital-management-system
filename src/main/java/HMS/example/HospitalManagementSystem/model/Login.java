package HMS.example.HospitalManagementSystem.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "login")   // optional, but good to be explicit
public class Login {

    @Id
    @Column(name = "username")
    private String username;

    @Column(name = "password")
    private String password;

    @Column(name = "role")
    private String role;

    // 🔹 NEW: email verification flag
    @Column(name = "email_verified")
    private Boolean emailVerified = Boolean.FALSE;

    // 🔹 NEW: verification code stored until verified
    @Column(name = "verification_code")
    private String verificationCode;

    public Login() {
        super();
    }

    public Login(String username, String password, String role) {
        super();
        this.username = username;
        this.password = password;
        this.role = role;
        this.emailVerified = Boolean.FALSE;
        this.verificationCode = null;
    }

    // You can also add a constructor including verificationCode if needed
    public Login(String username, String password, String role, String verificationCode) {
        super();
        this.username = username;
        this.password = password;
        this.role = role;
        this.emailVerified = Boolean.FALSE;
        this.verificationCode = verificationCode;
    }

    // -------- getters & setters --------

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public Boolean getEmailVerified() {
        return emailVerified;
    }

    public void setEmailVerified(Boolean emailVerified) {
        this.emailVerified = emailVerified;
    }

    public String getVerificationCode() {
        return verificationCode;
    }

    public void setVerificationCode(String verificationCode) {
        this.verificationCode = verificationCode;
    }

    @Override
    public String toString() {
        return "Login[" +
                "username=" + username +
                ", password=" + password +
                ", role=" + role +
                ", emailVerified=" + emailVerified +
                ", verificationCode=" + verificationCode +
                "]";
    }
}
