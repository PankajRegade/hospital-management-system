package HMS.example.HospitalManagementSystem.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource; // ✅ Added for attachments
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import HMS.example.HospitalManagementSystem.model.Appointment;
import HMS.example.HospitalManagementSystem.model.MedicalRecord;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

import java.io.File; // ✅ Added
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List; // ✅ Added

@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    @Autowired
    private JavaMailSender mailSender;

    @Value("${app.mail.from:no-reply@yourdomain.com}")
    private String fromAddress;

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm");

    /**
     * Attempt to send appointment confirmation.
     */
    public boolean sendAppointmentConfirmation(String toEmail, Appointment appointment) {
        if (toEmail == null || toEmail.trim().isEmpty()) {
            log.warn("No recipient email provided for appointment confirmation (appointment id {}).",
                    appointment != null ? appointment.getId() : null);
            return false;
        }

        if (mailSender == null) {
            log.error("JavaMailSender bean is null — spring-boot-starter-mail missing or not configured");
            return false;
        }

        try {
            log.info("Preparing appointment confirmation email to {}", toEmail);

            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            String subject = "Appointment confirmed — " +
                    (appointment != null && appointment.getAppointmentNumber() != null
                            ? appointment.getAppointmentNumber() : "");
            helper.setTo(toEmail);
            helper.setSubject(subject);
            helper.setFrom(fromAddress);

            String when = (appointment != null && appointment.getAppointmentTime() != null)
                    ? appointment.getAppointmentTime().format(DT_FMT) : "—";
            String doctor = (appointment != null && appointment.getDoctor() != null && appointment.getDoctor().getName() != null)
                    ? appointment.getDoctor().getName() : "Doctor";
            String patientName = (appointment != null && appointment.getPatient() != null && appointment.getPatient().getName() != null)
                    ? appointment.getPatient().getName() : "Patient";

            String html = "<!doctype html><html><head><meta charset='utf-8'/>" +
                    "<title>Appointment Confirmed</title></head><body>" +
                    "<div style='font-family:Arial,Helvetica,sans-serif;max-width:600px;margin:0 auto;padding:20px;'>" +
                    "<h2>Appointment Confirmed</h2>" +
                    "<p>Hi " + escapeHtml(patientName) + ",</p>" +
                    "<table style='width:100%;border-collapse:collapse'>" +
                    row("Appointment No", appointment != null && appointment.getAppointmentNumber() != null ? appointment.getAppointmentNumber() : "-") +
                    row("Doctor", doctor) +
                    row("When", when) +
                    row("Notes", appointment != null && appointment.getNotes() != null ? escapeHtml(appointment.getNotes()) : "-") +
                    "</table></div></body></html>";

            helper.setText(stripHtmlFallback(appointment, patientName), html);

            log.debug("Sending message subject='{}' to='{}' from='{}'", subject, toEmail, fromAddress);
            mailSender.send(message);
            log.info("Appointment confirmation email successfully sent to {} (appointment id {})",
                    toEmail, appointment != null ? appointment.getId() : null);
            return true;
        } catch (MessagingException me) {
            log.error("MessagingException sending appointment confirmation to {} : {}", toEmail, me.toString(), me);
            return false;
        } catch (Exception ex) {
            log.error("Unexpected exception sending appointment confirmation to {} : {}", toEmail, ex.toString(), ex);
            return false;
        }
    }

    /**
     * Notify patient when Doctor updates the appointment time (Reschedule).
     */
    public boolean sendAppointmentUpdatedByDoctor(String toEmail, String doctorName, LocalDateTime oldTime, LocalDateTime newTime) {
        if (toEmail == null || toEmail.trim().isEmpty()) {
            log.warn("No recipient email provided for appointment update.");
            return false;
        }

        if (mailSender == null) {
            log.error("JavaMailSender bean is null.");
            return false;
        }

        try {
            log.info("Preparing appointment update email to {}", toEmail);

            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            String subject = "Appointment Rescheduled - Dr. " + (doctorName != null ? doctorName : "");
            helper.setTo(toEmail);
            helper.setSubject(subject);
            helper.setFrom(fromAddress);

            String oldTimeStr = (oldTime != null) ? oldTime.format(DT_FMT) : "N/A";
            String newTimeStr = (newTime != null) ? newTime.format(DT_FMT) : "N/A";

            // HTML Body
            String html = "<!doctype html><html><head><meta charset='utf-8'/>" +
                    "<title>Appointment Updated</title></head><body>" +
                    "<div style='font-family:Arial,Helvetica,sans-serif;max-width:600px;margin:0 auto;padding:20px;'>" +
                    "<h2 style='color:#d97706;'>Appointment Rescheduled</h2>" +
                    "<p>Dear Patient,</p>" +
                    "<p>Your appointment with <strong>Dr. " + escapeHtmlStatic(doctorName) + "</strong> has been updated.</p>" +
                    "<table style='width:100%;border-collapse:collapse; margin-top:15px; margin-bottom:15px;'>" +
                    row("Previous Time", oldTimeStr) +
                    row("New Time", newTimeStr) +
                    "</table>" +
                    "<p>Please log in to your dashboard to view the full details.</p>" +
                    "</div></body></html>";

            // Text Fallback
            String text = "Appointment Rescheduled\n\n" +
                    "Doctor: " + doctorName + "\n" +
                    "Previous Time: " + oldTimeStr + "\n" +
                    "New Time: " + newTimeStr + "\n\n" +
                    "Please check your dashboard for details.";

            helper.setText(text, html);

            mailSender.send(message);
            log.info("Appointment update email successfully sent to {}", toEmail);
            return true;

        } catch (Exception ex) {
            log.error("Failed to send appointment update email to {}: {}", toEmail, ex.getMessage());
            return false;
        }
    }

    /**
     * Notify patient when Doctor CANCELS the appointment.
     */
    public boolean sendAppointmentCancelledByDoctor(String toEmail, String doctorName, LocalDateTime apptTime) {
        if (toEmail == null || toEmail.trim().isEmpty()) {
            return false;
        }

        if (mailSender == null) {
            log.error("JavaMailSender bean is null.");
            return false;
        }

        try {
            log.info("Preparing cancellation email to {}", toEmail);

            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            String subject = "Appointment Cancelled - Dr. " + (doctorName != null ? doctorName : "");
            helper.setTo(toEmail);
            helper.setSubject(subject);
            helper.setFrom(fromAddress);

            String timeStr = (apptTime != null) ? apptTime.format(DT_FMT) : "N/A";

            // HTML Body (Red Theme)
            String html = "<!doctype html><html><head><meta charset='utf-8'/>" +
                    "<title>Appointment Cancelled</title></head><body>" +
                    "<div style='font-family:Arial,Helvetica,sans-serif;max-width:600px;margin:0 auto;padding:20px;'>" +
                    "<h2 style='color:#ef4444;'>Appointment Cancelled</h2>" +
                    "<p>Dear Patient,</p>" +
                    "<p>We regret to inform you that your appointment with <strong>Dr. " + escapeHtmlStatic(doctorName) + "</strong> has been cancelled.</p>" +
                    "<div style='background:#fee2e2; border-left:4px solid #ef4444; padding:15px; margin:20px 0; color:#991b1b;'>" +
                    "<strong>Cancelled Appointment:</strong><br/>" + timeStr +
                    "</div>" +
                    "<p>We apologize for any inconvenience. Please visit your dashboard to book a new slot.</p>" +
                    "</div></body></html>";

            // Text Fallback
            String text = "Appointment Cancelled\n\n" +
                    "Your appointment with Dr. " + doctorName + " on " + timeStr + " has been cancelled.\n\n" +
                    "We apologize for the inconvenience. Please login to book a new appointment.";

            helper.setText(text, html);

            mailSender.send(message);
            log.info("Cancellation email sent to {}", toEmail);
            return true;

        } catch (Exception ex) {
            log.error("Failed to send cancellation email to {}: {}", toEmail, ex.getMessage());
            return false;
        }
    }

    /**
     * 🔹 SEND MEDICAL RECORD TO PATIENT (WITH ATTACHMENTS)
     */
    public boolean sendMedicalRecordToPatient(String toEmail, String doctorName, MedicalRecord record, List<File> filesToAttach) {
        if (toEmail == null || toEmail.trim().isEmpty()) {
            return false;
        }

        if (mailSender == null) {
            log.error("JavaMailSender bean is null.");
            return false;
        }

        try {
            int attachmentCount = (filesToAttach != null) ? filesToAttach.size() : 0;
            log.info("Sending medical record email to {} with {} attachments", toEmail, attachmentCount);

            MimeMessage message = mailSender.createMimeMessage();
            // True = multipart (allows attachments)
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            String subject = "Medical Record Summary - Dr. " + doctorName;
            helper.setTo(toEmail);
            helper.setSubject(subject);
            helper.setFrom(fromAddress);

            // Format Date
            String dateStr = (record.getRecordDate() != null) 
                ? record.getRecordDate().format(DateTimeFormatter.ofPattern("dd MMM yyyy")) 
                : "N/A";

            String html = "<!doctype html><html><head><meta charset='utf-8'/></head><body>" +
                    "<div style='font-family:Arial,sans-serif;max-width:600px;margin:0 auto;padding:20px;border:1px solid #eee;'>" +
                    "<h2 style='color:#0ea5e9;'>Medical Record Summary</h2>" +
                    "<p>Dear Patient,</p>" +
                    "<p>Here is the summary of your recent visit with <strong>Dr. " + escapeHtmlStatic(doctorName) + "</strong>.</p>" +
                    
                    "<table style='width:100%; border-collapse:collapse; margin-top:20px;'>" +
                    row("Date", dateStr) +
                    row("Diagnosis", record.getDiagnosis()) +
                    row("Prescription", record.getPrescription()) +
                    row("Treatment Plan", record.getTreatment()) +
                    "</table>" +

                    "<p style='margin-top:15px;'><strong>Attachments:</strong> " + attachmentCount + " file(s) attached.</p>" +

                    "<p style='margin-top:20px; color:#64748b; font-size:12px;'>" +
                    "Disclaimer: This email contains private medical information. If you received this in error, please delete it immediately." +
                    "</p>" +
                    "</div></body></html>";

            helper.setText(html, true); // true = HTML

            // 📎 ATTACH FILES LOGIC
            if (filesToAttach != null && !filesToAttach.isEmpty()) {
                for (File file : filesToAttach) {
                    if (file.exists()) {
                        FileSystemResource fileResource = new FileSystemResource(file);
                        helper.addAttachment(file.getName(), fileResource);
                    }
                }
            }

            mailSender.send(message);
            log.info("Medical record email successfully sent to {}", toEmail);
            return true;

        } catch (Exception ex) {
            log.error("Failed to send medical record email: {}", ex.getMessage());
            return false;
        }
    }
    
    // Overload method for backward compatibility (no attachments)
    public boolean sendMedicalRecordToPatient(String toEmail, String doctorName, MedicalRecord record) {
        return sendMedicalRecordToPatient(toEmail, doctorName, record, null);
    }

    /**
     * Send email verification link (for patient & doctor signup).
     */
    public boolean sendVerificationEmail(String toEmail, String verifyLink) {
        if (toEmail == null || toEmail.trim().isEmpty()) {
            log.warn("No recipient email provided for verification email.");
            return false;
        }

        if (mailSender == null) {
            log.error("JavaMailSender bean is null — spring-boot-starter-mail missing or not configured");
            return false;
        }

        try {
            log.info("Preparing verification email to {}", toEmail);

            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            String subject = "Verify your email - Axes Hospital";
            helper.setTo(toEmail);
            helper.setSubject(subject);
            helper.setFrom(fromAddress);

            String safeLink = escapeHtmlStatic(verifyLink);

            String html = "<!doctype html><html><head><meta charset='utf-8'/>" +
                    "<title>Email Verification</title></head><body>" +
                    "<div style='font-family:Arial,Helvetica,sans-serif;max-width:600px;margin:0 auto;padding:20px;'>" +
                    "<h2>Verify your email</h2>" +
                    "<p>Hi,</p>" +
                    "<p>Thank you for registering with Axes Hospital Management System.</p>" +
                    "<p>Please click the button below to verify your email address:</p>" +
                    "<p style='margin:24px 0;'>" +
                    "<a href='" + safeLink + "' " +
                    "style='background:#2563eb;color:#ffffff;text-decoration:none;padding:10px 20px;" +
                    "border-radius:6px;display:inline-block;font-weight:bold;'>Verify Email</a>" +
                    "</p>" +
                    "<p>If the button doesn't work, copy and paste this link in your browser:</p>" +
                    "<p><a href='" + safeLink + "'>" + safeLink + "</a></p>" +
                    "<p style='margin-top:30px;font-size:12px;color:#6b7280;'>If you did not sign up, you can ignore this email.</p>" +
                    "</div></body></html>";

            String textFallback = "Verify your email\n\n"
                    + "Please open this link in your browser:\n"
                    + verifyLink + "\n\n"
                    + "If you did not sign up, you can ignore this email.";

            helper.setText(textFallback, html);

            log.debug("Sending verification email subject='{}' to='{}' from='{}'", subject, toEmail, fromAddress);
            mailSender.send(message);
            log.info("Verification email successfully sent to {}", toEmail);
            return true;
        } catch (MessagingException me) {
            log.error("MessagingException sending verification email to {} : {}", toEmail, me.toString(), me);
            return false;
        } catch (Exception ex) {
            log.error("Unexpected exception sending verification email to {} : {}", toEmail, ex.toString(), ex);
            return false;
        }
    }

    // ✅ Simple generic mail sender used by Admin (approve/reject doctor, etc.)
    public boolean sendSimpleMail(String toEmail, String subject, String body) {
        if (toEmail == null || toEmail.trim().isEmpty()) {
            log.warn("No recipient email provided for simple mail.");
            return false;
        }

        if (mailSender == null) {
            log.error("JavaMailSender bean is null — spring-boot-starter-mail missing or not configured");
            return false;
        }

        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setTo(toEmail);
            msg.setSubject(subject != null ? subject : "");
            msg.setText(body != null ? body : "");
            msg.setFrom(fromAddress);

            log.debug("Sending simple mail subject='{}' to='{}' from='{}'", subject, toEmail, fromAddress);
            mailSender.send(msg);
            log.info("Simple email successfully sent to {}", toEmail);
            return true;
        } catch (Exception ex) {
            log.error("Unexpected exception sending simple email to {} : {}", toEmail, ex.toString(), ex);
            return false;
        }
    }

    // ✅ Password reset email
    public boolean sendPasswordResetEmail(String toEmail, String resetLink) {
        if (toEmail == null || toEmail.trim().isEmpty()) {
            log.warn("No recipient email provided for password reset.");
            return false;
        }

        String subject = "Reset your HMS account password";
        String text = "We received a request to reset your password.\n\n"
                + "Click the link below to choose a new password:\n"
                + resetLink + "\n\n"
                + "If you did not request this, you can safely ignore this email.";

        return sendSimpleMail(toEmail, subject, text);
    }

    // -------------------------------------------------------------------------
    // HELPERS
    // -------------------------------------------------------------------------

    private static String row(String title, String value) {
        return "<tr><td style='padding:6px;border:1px solid #eee; width:40%;'><strong>" + escapeHtmlStatic(title) + "</strong></td>"
                + "<td style='padding:6px;border:1px solid #eee'>" + escapeHtmlStatic(value) + "</td></tr>";
    }

    private String escapeHtml(String s) { return escapeHtmlStatic(s); }

    private static String escapeHtmlStatic(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private String stripHtmlFallback(Appointment appointment, String patientName) {
        String when = (appointment != null && appointment.getAppointmentTime() != null)
                ? appointment.getAppointmentTime().format(DT_FMT) : "—";
        String doctor = (appointment != null && appointment.getDoctor() != null && appointment.getDoctor().getName() != null)
                ? appointment.getDoctor().getName() : "Doctor";

        StringBuilder sb = new StringBuilder();
        sb.append("Appointment confirmed\n");
        sb.append("Patient: ").append(patientName != null ? patientName : "Patient").append("\n");
        sb.append("Appointment No: ")
                .append(appointment != null && appointment.getAppointmentNumber() != null ? appointment.getAppointmentNumber() : "-")
                .append("\n");
        sb.append("Doctor: ").append(doctor).append("\n");
        sb.append("When: ").append(when).append("\n");
        sb.append("Notes: ")
                .append(appointment != null && appointment.getNotes() != null ? appointment.getNotes() : "-")
                .append("\n");
        return sb.toString();
    }
}