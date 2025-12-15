package HMS.example.HospitalManagementSystem.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "doctor")
public class Doctor {

    // ---------------- APPROVAL FIELDS ----------------

    @Column(nullable = false)
    private boolean approved = false;     // default: waiting for admin approval

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;     // timestamp when admin approves doctor


    // ---------------- BASIC FIELDS -------------------

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    private String email;

    private String phone;

    @Column(name = "specialization")
    private String specialization;

    // ---- NEW FIELD: PROFILE PHOTO PATH (relative URL like /uploads/doctor-1.jpg) ----
    @Column(name = "photo_path")
    private String photoPath;

    // ---- NEW FIELD: has doctor completed initial details? ----
    @Column(name = "details_completed", nullable = false)
    private Boolean detailsCompleted = Boolean.FALSE;


    // ---------------- CONSTRUCTORS -------------------

    public Doctor() {}

    // ---------------- GETTERS & SETTERS --------------

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

    public boolean isApproved() {
        return approved;
    }

    public void setApproved(boolean approved) {
        this.approved = approved;
    }

    public LocalDateTime getApprovedAt() {
        return approvedAt;
    }

    public void setApprovedAt(LocalDateTime approvedAt) {
        this.approvedAt = approvedAt;
    }

    // ---- NEW GETTER/SETTER FOR PHOTO PATH ----

    public String getPhotoPath() {
        return photoPath;
    }

    public void setPhotoPath(String photoPath) {
        this.photoPath = photoPath;
    }

    // ---- NEW GETTER/SETTER FOR details_completed ----

    /**
     * Returns true when detailsCompleted is non-null and true.
     * Keeps compatibility with code that calls getDetailsCompleted().
     */
    public Boolean getDetailsCompleted() {
        return detailsCompleted != null && detailsCompleted;
    }

    public void setDetailsCompleted(Boolean detailsCompleted) {
        this.detailsCompleted = detailsCompleted;
    }

    // ---------------- TO STRING -----------------------

    @Override
    public String toString() {
        return "Doctor{" +
                "id=" + id +
                ", name='" + name + '\'' +
                ", email='" + email + '\'' +
                ", phone='" + phone + '\'' +
                ", specialization='" + specialization + '\'' +
                ", approved=" + approved +
                ", approvedAt=" + approvedAt +
                ", photoPath='" + photoPath + '\'' +
                ", detailsCompleted=" + detailsCompleted +
                '}';
    }
}
