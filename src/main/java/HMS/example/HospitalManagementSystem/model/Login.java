package HMS.example.HospitalManagementSystem.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime; // 1. Import for date handling

@Entity
@Table(name = "login")
public class Login {

    @Id
    @Column(name = "username")
    private String username;

    @Column(name = "password")
    private String password;

    @Column(name = "role")
    private String role;

    @Column(name = "email_verified")
    private Boolean emailVerified = Boolean.FALSE;

    @Column(name = "verification_code")
    private String verificationCode;

    // 🔹 NEW: Tracks if the user is currently online (for Admin view/logic)
    @Column(name = "is_logged_in")
    private Boolean isLoggedIn = false;

    // 🔹 NEW: Tracks the exact time of the last successful login
    @Column(name = "last_login_date")
    private LocalDateTime lastLoginDate;

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
        this.isLoggedIn = false;
    }

    public Login(String username, String password, String role, String verificationCode) {
        super();
        this.username = username;
        this.password = password;
        this.role = role;
        this.emailVerified = Boolean.FALSE;
        this.verificationCode = verificationCode;
        this.isLoggedIn = false;
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

    // -------- NEW getters & setters --------

    public Boolean getIsLoggedIn() {
        return isLoggedIn;
    }

    public void setIsLoggedIn(Boolean isLoggedIn) {
        this.isLoggedIn = isLoggedIn;
    }

    public LocalDateTime getLastLoginDate() {
        return lastLoginDate;
    }

    public void setLastLoginDate(LocalDateTime lastLoginDate) {
        this.lastLoginDate = lastLoginDate;
    }

    @Override
    public String toString() {
        return "Login[" +
                "username=" + username +
                ", role=" + role +
                ", emailVerified=" + emailVerified +
                ", isLoggedIn=" + isLoggedIn +
                "]";
    }
}