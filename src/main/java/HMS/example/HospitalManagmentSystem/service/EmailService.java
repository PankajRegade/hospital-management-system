package HMS.example.HospitalManagmentSystem.service;

import HMS.example.HospitalManagmentSystem.model.Appointment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.time.format.DateTimeFormatter;

// ✅ simple text emails
import org.springframework.mail.SimpleMailMessage;

@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    @Autowired
    private JavaMailSender mailSender;

    @Value("${app.mail.from:no-reply@yourdomain.com}")
    private String fromAddress;

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm");

    /**
     * Attempt to send appointment confirmation. Returns true on success, false otherwise.
     * Does not throw checked jakarta.mail exceptions to callers.
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
     * 🔹 Send email verification link (for patient & doctor signup).
     * Returns true on success, false otherwise.
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

    // ---------- helpers ----------

    private static String row(String title, String value) {
        return "<tr><td style='padding:6px;border:1px solid #eee'><strong>" + escapeHtmlStatic(title) + "</strong></td>"
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

    // ✅ NEW: Password reset email (for patient & doctor)
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

        // Reuse simple mail helper
        return sendSimpleMail(toEmail, subject, text);
    }
}
