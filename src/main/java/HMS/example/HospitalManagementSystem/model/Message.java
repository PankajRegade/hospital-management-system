package HMS.example.HospitalManagementSystem.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "message")
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // who sent message (clinic, system, doctor)
    @Column(nullable = false)
    private String fromUser;

    // short text message
    @Column(nullable = false, columnDefinition = "TEXT")
    private String text;

    // timestamp of message
    @Column(name = "sent_at", nullable = false)
    private LocalDateTime sentAt;

    // many messages belong to one patient
    @ManyToOne(optional = false)
    @JoinColumn(name = "patient_id")
    private Patient patient;

    public Message() {}

    @PrePersist
    protected void onCreate() {
        if (sentAt == null) {
            sentAt = LocalDateTime.now();
        }
    }

    // ------ getters & setters ------

    public Long getId() { return id; }

    public String getFromUser() { return fromUser; }
    public void setFromUser(String fromUser) { this.fromUser = fromUser; }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public LocalDateTime getSentAt() { return sentAt; }
    public void setSentAt(LocalDateTime sentAt) { this.sentAt = sentAt; }

    public Patient getPatient() { return patient; }
    public void setPatient(Patient patient) { this.patient = patient; }
}
