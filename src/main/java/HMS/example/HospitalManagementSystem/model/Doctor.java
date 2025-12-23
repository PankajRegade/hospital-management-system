package HMS.example.HospitalManagementSystem.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "doctor")
public class Doctor {

    // ---------------- PRIMARY KEY ----------------

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ---------------- BASIC DETAILS ----------------

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String email;

    private String phone;

    @Column(nullable = false)
    private String specialization;

    // ---------------- PROFILE INFO ----------------

    // Short professional description (USED BY CARDIOLOGY PAGE)
    @Column(columnDefinition = "TEXT")
    private String bio;

    // Relative URL like /uploads/doctor-1.jpg
    @Column(name = "photo_path")
    private String photoPath;

    // Has doctor completed profile details?
    @Column(name = "details_completed", nullable = false)
    private Boolean detailsCompleted = Boolean.FALSE;

    // ---------------- APPROVAL FLOW ----------------

    @Column(nullable = false)
    private boolean approved = false;

    @Column(nullable = false)
    private boolean rejected = false;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    // ---------------- CONSTRUCTORS ----------------

    public Doctor() {}

    // ---------------- GETTERS & SETTERS ------------

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getSpecialization() {
        return specialization;
    }

    public void setSpecialization(String specialization) {
        this.specialization = specialization;
    }

    // ---------- BIO (FIXES 500 ERROR) ----------

    public String getBio() {
        return bio;
    }

    public void setBio(String bio) {
        this.bio = bio;
    }

    // ---------- PHOTO PATH ----------

    public String getPhotoPath() {
        return photoPath;
    }

    public void setPhotoPath(String photoPath) {
        this.photoPath = photoPath;
    }

    // ---------- DETAILS COMPLETED ----------

    public Boolean getDetailsCompleted() {
        return detailsCompleted != null && detailsCompleted;
    }

    public void setDetailsCompleted(Boolean detailsCompleted) {
        this.detailsCompleted = detailsCompleted;
    }

    // ---------- APPROVAL ----------

    public boolean isApproved() {
        return approved;
    }

    public void setApproved(boolean approved) {
        this.approved = approved;
    }

    public boolean isRejected() {
        return rejected;
    }

    public void setRejected(boolean rejected) {
        this.rejected = rejected;
    }

    public LocalDateTime getApprovedAt() {
        return approvedAt;
    }

    public void setApprovedAt(LocalDateTime approvedAt) {
        this.approvedAt = approvedAt;
    }

    // ---------------- TO STRING ----------------

    @Override
    public String toString() {
        return "Doctor{" +
                "id=" + id +
                ", name='" + name  + '\'' +
                ", email='" + email + '\'' +
                ", phone='" + phone + '\'' +
                ", specialization='" + specialization + '\'' +
                ", approved=" + approved +
                ", rejected=" + rejected +
                ", approvedAt=" + approvedAt +
                ", photoPath='" + photoPath + '\'' +
                ", detailsCompleted=" + detailsCompleted +
                '}';
    }
}
