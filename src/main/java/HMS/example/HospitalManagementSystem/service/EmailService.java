package HMS.example.HospitalManagementSystem.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import HMS.example.HospitalManagementSystem.model.Appointment;
import HMS.example.HospitalManagementSystem.model.MedicalRecord;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    @Autowired
    private JavaMailSender mailSender;

    @Value("${app.mail.from:no-reply@yourdomain.com}")
    private String fromAddress;

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm");

    // =================================================================================
    // 1. VERIFICATION EMAIL (Used for Signup, Resend, and Change Email)
    // =================================================================================
    public boolean sendVerificationEmail(String toEmail, String verifyLink) {
        if (isInvalid(toEmail)) return false;

        try {
            log.info("Preparing verification email to {}", toEmail);

            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            String subject = "Verify your email - Axes Hospital";
            helper.setTo(toEmail);
            helper.setSubject(subject);
            helper.setFrom(fromAddress);

            String safeLink = escapeHtmlStatic(verifyLink);

            // HTML Body with Button
            String html = "<!doctype html><html><head><meta charset='utf-8'/></head><body style='font-family:Arial,sans-serif;background-color:#f9f9f9;padding:20px;'>" +
                    "<div style='max-width:600px;margin:0 auto;background:#fff;padding:30px;border-radius:8px;box-shadow:0 2px 5px rgba(0,0,0,0.1);'>" +
                    "<h2 style='color:#2563eb;text-align:center;'>Verify Your Email</h2>" +
                    "<p>Hello,</p>" +
                    "<p>Thank you for registering with Axes Hospital Management System. Please verify your email to activate your account.</p>" +
                    "<div style='text-align:center;margin:30px 0;'>" +
                    "<a href='" + safeLink + "' style='background:#2563eb;color:#ffffff;text-decoration:none;padding:12px 24px;border-radius:5px;font-weight:bold;font-size:16px;'>Verify Account</a>" +
                    "</div>" +
                    "<p>Or copy this link:</p>" +
                    "<p><a href='" + safeLink + "' style='color:#6b7280;word-break:break-all;'>" + safeLink + "</a></p>" +
                    "<hr style='border:none;border-top:1px solid #eee;margin:20px 0;'/>" +
                    "<p style='font-size:12px;color:#999;'>If you didn't create an account, you can safely ignore this email.</p>" +
                    "</div></body></html>";

            helper.setText("Please verify your email: " + verifyLink, html);

            mailSender.send(message);
            log.info("Verification email sent to {}", toEmail);
            return true;
        } catch (Exception ex) {
            log.error("Error sending verification email to {}: {}", toEmail, ex.getMessage());
            return false;
        }
    }

    // =================================================================================
    // 2. PASSWORD RESET EMAIL (Upgraded to HTML)
    // =================================================================================
    public boolean sendPasswordResetEmail(String toEmail, String resetLink) {
        if (isInvalid(toEmail)) return false;

        try {
            log.info("Preparing password reset email to {}", toEmail);
            
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            String subject = "Reset Your Password - HMS";
            helper.setTo(toEmail);
            helper.setSubject(subject);
            helper.setFrom(fromAddress);

            String safeLink = escapeHtmlStatic(resetLink);

            String html = "<!doctype html><html><head><meta charset='utf-8'/></head><body style='font-family:Arial,sans-serif;background-color:#f9f9f9;padding:20px;'>" +
                    "<div style='max-width:600px;margin:0 auto;background:#fff;padding:30px;border-radius:8px;box-shadow:0 2px 5px rgba(0,0,0,0.1);'>" +
                    "<h2 style='color:#d97706;text-align:center;'>Password Reset Request</h2>" +
                    "<p>Hello,</p>" +
                    "<p>We received a request to reset your password. Click the button below to choose a new one.</p>" +
                    "<div style='text-align:center;margin:30px 0;'>" +
                    "<a href='" + safeLink + "' style='background:#d97706;color:#ffffff;text-decoration:none;padding:12px 24px;border-radius:5px;font-weight:bold;font-size:16px;'>Reset Password</a>" +
                    "</div>" +
                    "<p style='font-size:12px;color:#999;'>If you didn't request this, you can safely ignore this email.</p>" +
                    "</div></body></html>";

            helper.setText("Reset your password here: " + resetLink, html);

            mailSender.send(message);
            log.info("Password reset email sent to {}", toEmail);
            return true;
        } catch (Exception ex) {
            log.error("Error sending password reset email to {}: {}", toEmail, ex.getMessage());
            return false;
        }
    }

    // =================================================================================
    // 3. APPOINTMENT CONFIRMATION
    // =================================================================================
    public boolean sendAppointmentConfirmation(String toEmail, Appointment appointment) {
        if (isInvalid(toEmail)) return false;

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            String apptNo = (appointment != null && appointment.getAppointmentNumber() != null) ? appointment.getAppointmentNumber() : "";
            String subject = "Appointment Confirmed - " + apptNo;
            
            helper.setTo(toEmail);
            helper.setSubject(subject);
            helper.setFrom(fromAddress);

            String when = (appointment != null && appointment.getAppointmentTime() != null) ? appointment.getAppointmentTime().format(DT_FMT) : "—";
            String doctor = (appointment != null && appointment.getDoctor() != null) ? appointment.getDoctor().getName() : "Doctor";
            String patientName = (appointment != null && appointment.getPatient() != null) ? appointment.getPatient().getName() : "Patient";

            String html = "<!doctype html><html><body style='font-family:Arial,sans-serif;padding:20px;'>" +
                    "<div style='max-width:600px;margin:0 auto;border:1px solid #eee;padding:20px;'>" +
                    "<h2 style='color:#16a34a;'>Appointment Confirmed</h2>" +
                    "<p>Hi " + escapeHtml(patientName) + ",</p>" +
                    "<table style='width:100%;border-collapse:collapse;margin-top:15px;'>" +
                    row("Appointment No", apptNo) +
                    row("Doctor", doctor) +
                    row("When", when) +
                    row("Notes", appointment != null ? appointment.getNotes() : "") +
                    "</table></div></body></html>";

            helper.setText("Appointment Confirmed: " + apptNo, html);
            mailSender.send(message);
            return true;
        } catch (Exception ex) {
            log.error("Error sending appointment confirmation: {}", ex.getMessage());
            return false;
        }
    }

    // =================================================================================
    // 4. APPOINTMENT UPDATED (Reschedule)
    // =================================================================================
    public boolean sendAppointmentUpdatedByDoctor(String toEmail, String doctorName, LocalDateTime oldTime, LocalDateTime newTime) {
        if (isInvalid(toEmail)) return false;

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setTo(toEmail);
            helper.setSubject("Appointment Rescheduled - Dr. " + doctorName);
            helper.setFrom(fromAddress);

            String oldTimeStr = (oldTime != null) ? oldTime.format(DT_FMT) : "N/A";
            String newTimeStr = (newTime != null) ? newTime.format(DT_FMT) : "N/A";

            String html = "<!doctype html><html><body style='font-family:Arial,sans-serif;padding:20px;'>" +
                    "<div style='max-width:600px;margin:0 auto;border:1px solid #eee;padding:20px;'>" +
                    "<h2 style='color:#d97706;'>Appointment Updated</h2>" +
                    "<p>Your appointment with <strong>Dr. " + escapeHtmlStatic(doctorName) + "</strong> has been rescheduled.</p>" +
                    "<table style='width:100%;border-collapse:collapse;margin-top:15px;'>" +
                    row("Previous Time", oldTimeStr) +
                    row("New Time", newTimeStr) +
                    "</table></div></body></html>";

            helper.setText("Appointment Rescheduled. New Time: " + newTimeStr, html);
            mailSender.send(message);
            return true;
        } catch (Exception ex) {
            log.error("Error sending update email: {}", ex.getMessage());
            return false;
        }
    }

    // =================================================================================
    // 5. APPOINTMENT CANCELLED
    // =================================================================================
    public boolean sendAppointmentCancelledByDoctor(String toEmail, String doctorName, LocalDateTime apptTime) {
        if (isInvalid(toEmail)) return false;

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setTo(toEmail);
            helper.setSubject("Appointment Cancelled - Dr. " + doctorName);
            helper.setFrom(fromAddress);

            String timeStr = (apptTime != null) ? apptTime.format(DT_FMT) : "N/A";

            String html = "<!doctype html><html><body style='font-family:Arial,sans-serif;padding:20px;'>" +
                    "<div style='max-width:600px;margin:0 auto;border:1px solid #eee;padding:20px;'>" +
                    "<h2 style='color:#ef4444;'>Appointment Cancelled</h2>" +
                    "<p>Your appointment with <strong>Dr. " + escapeHtmlStatic(doctorName) + "</strong> on " + timeStr + " has been cancelled.</p>" +
                    "<p>Please login to your dashboard to book a new slot.</p>" +
                    "</div></body></html>";

            helper.setText("Appointment Cancelled.", html);
            mailSender.send(message);
            return true;
        } catch (Exception ex) {
            log.error("Error sending cancellation email: {}", ex.getMessage());
            return false;
        }
    }

    // =================================================================================
    // 6. MEDICAL RECORD (With Attachments)
    // =================================================================================
    public boolean sendMedicalRecordToPatient(String toEmail, String doctorName, MedicalRecord record, List<File> filesToAttach) {
        if (isInvalid(toEmail)) return false;

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setTo(toEmail);
            helper.setSubject("Medical Record Summary - Dr. " + doctorName);
            helper.setFrom(fromAddress);

            String dateStr = (record.getRecordDate() != null) ? record.getRecordDate().format(DateTimeFormatter.ofPattern("dd MMM yyyy")) : "N/A";

            String html = "<!doctype html><html><body style='font-family:Arial,sans-serif;padding:20px;'>" +
                    "<div style='max-width:600px;margin:0 auto;border:1px solid #eee;padding:20px;'>" +
                    "<h2 style='color:#0ea5e9;'>Medical Record Summary</h2>" +
                    "<p>Summary of visit with <strong>Dr. " + escapeHtmlStatic(doctorName) + "</strong>.</p>" +
                    "<table style='width:100%;border-collapse:collapse;margin-top:15px;'>" +
                    row("Date", dateStr) +
                    row("Diagnosis", record.getDiagnosis()) +
                    row("Prescription", record.getPrescription()) +
                    row("Treatment", record.getTreatment()) +
                    "</table>" +
                    "<p><strong>Attachments:</strong> " + ((filesToAttach != null) ? filesToAttach.size() : 0) + "</p>" +
                    "</div></body></html>";

            helper.setText(html, true);

            if (filesToAttach != null) {
                for (File file : filesToAttach) {
                    if (file.exists()) {
                        FileSystemResource fileResource = new FileSystemResource(file);
                        helper.addAttachment(file.getName(), fileResource);
                    }
                }
            }

            mailSender.send(message);
            return true;
        } catch (Exception ex) {
            log.error("Error sending medical record email: {}", ex.getMessage());
            return false;
        }
    }

    public boolean sendMedicalRecordToPatient(String toEmail, String doctorName, MedicalRecord record) {
        return sendMedicalRecordToPatient(toEmail, doctorName, record, null);
    }

    // =================================================================================
    // 7. SIMPLE MAIL (Admin/Generic)
    // =================================================================================
    public boolean sendSimpleMail(String toEmail, String subject, String body) {
        if (isInvalid(toEmail)) return false;

        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setTo(toEmail);
            msg.setSubject(subject);
            msg.setText(body);
            msg.setFrom(fromAddress);
            mailSender.send(msg);
            return true;
        } catch (Exception ex) {
            log.error("Error sending simple email: {}", ex.getMessage());
            return false;
        }
    }

    // =================================================================================
    // HELPERS
    // =================================================================================
    
    private boolean isInvalid(String email) {
        if (email == null || email.trim().isEmpty()) {
            log.warn("Email sending aborted: Recipient email is missing.");
            return true;
        }
        if (mailSender == null) {
            log.error("Email sending aborted: JavaMailSender is null.");
            return true;
        }
        return false;
    }

    private static String row(String title, String value) {
        return "<tr><td style='padding:8px;border:1px solid #ddd;width:35%;font-weight:bold;'>" + escapeHtmlStatic(title) + "</td>"
                + "<td style='padding:8px;border:1px solid #ddd;'>" + escapeHtmlStatic(value) + "</td></tr>";
    }

    private String escapeHtml(String s) { return escapeHtmlStatic(s); }

    private static String escapeHtmlStatic(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}