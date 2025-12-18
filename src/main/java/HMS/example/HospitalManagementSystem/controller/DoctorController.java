package HMS.example.HospitalManagementSystem.controller;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.query.Query;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import HMS.example.HospitalManagementSystem.model.Appointment;
import HMS.example.HospitalManagementSystem.model.AppointmentStatus;
import HMS.example.HospitalManagementSystem.model.Doctor;
import HMS.example.HospitalManagementSystem.model.MedicalRecord;
import HMS.example.HospitalManagementSystem.model.Patient;
// If you have a RecordReport model for database tracking of files, import it here
// import HMS.example.HospitalManagementSystem.model.RecordReport; 
import HMS.example.HospitalManagementSystem.service.EmailService;
import jakarta.servlet.http.HttpSession;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Controller
@RequestMapping("/doctor")
public class DoctorController {

    @Autowired
    private SessionFactory sf;

    @Autowired
    private EmailService emailService;

    // Define storage location for uploaded files
    private final Path storageDir = Paths.get(System.getProperty("user.dir"), "uploads", "reports");

    public DoctorController() {
        // Create upload directory if it doesn't exist
        try {
            Files.createDirectories(storageDir);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ---------------------------------------------------
    // Helper
    // ---------------------------------------------------
    private Long toLong(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Number) return ((Number) obj).longValue();
        try { return Long.parseLong(obj.toString()); } catch (Exception e) { return null; }
    }

    // ===================================================
    // DOCTOR DASHBOARD (UPCOMING APPOINTMENTS ONLY)
    // ===================================================
    @GetMapping({"", "/", "/dashboard"})
    public String dashboard(Model model, HttpSession session) {

        Long doctorId = toLong(session.getAttribute("doctorId"));
        if (doctorId == null) {
            model.addAttribute("msg", "Please login as doctor.");
            return "home";
        }

        Session ss = sf.openSession();
        try {
            Doctor doctor = ss.get(Doctor.class, doctorId);
            if (doctor == null) {
                model.addAttribute("msg", "Doctor record not found.");
                return "home";
            }

            model.addAttribute("doctor", doctor);
            model.addAttribute("doctorName", doctor.getName());
            model.addAttribute("detailsCompleted", doctor.getDetailsCompleted());

            // 🔹 UPCOMING APPOINTMENTS ONLY
            Query<Appointment> q = ss.createQuery(
                "from Appointment a " +
                "where a.doctor.id = :did " +
                "and a.appointmentTime >= :now " +
                "and a.status <> :cancelled " +
                "order by a.appointmentTime asc",
                Appointment.class
            );

            q.setParameter("did", doctorId);
            q.setParameter("now", LocalDateTime.now());
            q.setParameter("cancelled", AppointmentStatus.CANCELLED);

            List<Appointment> appointments = q.list();
            model.addAttribute("appointments",
                    appointments != null ? appointments : Collections.emptyList());

            return "doctor_dashboard";

        } finally {
            ss.close();
        }
    }

    // ===================================================
    // VIEW SINGLE APPOINTMENT (EXISTING)
    // ===================================================
    @GetMapping("/appointments/{id}")
    public String viewAppointment(@PathVariable Long id,
                                  Model model,
                                  HttpSession session) {

        Long doctorId = toLong(session.getAttribute("doctorId"));
        if (doctorId == null) {
            model.addAttribute("msg", "Please login.");
            return "home";
        }

        Session ss = sf.openSession();
        try {
            Appointment ap = ss.get(Appointment.class, id);

            if (ap == null || !doctorId.equals(ap.getDoctor().getId())) {
                model.addAttribute("msg", "Access denied.");
                return "redirect:/doctor/dashboard";
            }

            model.addAttribute("appointment", ap);
            model.addAttribute("doctorName", session.getAttribute("doctorName"));
            return "doctor_appointment_details";

        } finally {
            ss.close();
        }
    }

    // ===================================================
    // EDIT APPOINTMENT (GET)
    // ===================================================
    @GetMapping("/appointments/{id}/edit")
    public String editAppointmentForm(@PathVariable Long id,
                                      Model model,
                                      HttpSession session) {

        Long doctorId = toLong(session.getAttribute("doctorId"));
        if (doctorId == null) {
            model.addAttribute("msg", "Please login.");
            return "home";
        }

        Session ss = sf.openSession();
        try {
            Appointment ap = ss.get(Appointment.class, id);

            if (ap == null || !doctorId.equals(ap.getDoctor().getId())) {
                model.addAttribute("msg", "Access denied.");
                return "redirect:/doctor/dashboard";
            }

            model.addAttribute("appointment", ap);
            return "doctor_appointment_edit";

        } finally {
            ss.close();
        }
    }

    // ===================================================
    // UPDATE APPOINTMENT (POST)
    // ===================================================
    @PostMapping("/appointments/{id}/update")
    public String updateAppointment(@PathVariable Long id,
                                    @RequestParam String appointmentTime,
                                    @RequestParam String status,
                                    @RequestParam(required = false) String notes,
                                    HttpSession session,
                                    Model model) {

        Long doctorId = toLong(session.getAttribute("doctorId"));
        if (doctorId == null) {
            model.addAttribute("msg", "Please login.");
            return "home";
        }

        Session ss = sf.openSession();
        Transaction tx = null;

        try {
            tx = ss.beginTransaction();
            Appointment ap = ss.get(Appointment.class, id);

            if (ap == null || !doctorId.equals(ap.getDoctor().getId())) {
                model.addAttribute("msg", "Access denied.");
                return "redirect:/doctor/dashboard";
            }

            // ✅ CAPTURE OLD TIME BEFORE UPDATE
            LocalDateTime oldTime = ap.getAppointmentTime();

            // UPDATE APPOINTMENT
            LocalDateTime newTime = LocalDateTime.parse(appointmentTime);
            ap.setAppointmentTime(newTime);
            ap.setStatus(AppointmentStatus.valueOf(status));
            ap.setNotes(notes);

            ss.update(ap);
            tx.commit();

            // ✅ SEND EMAIL AFTER COMMIT
            try {
                Patient patient = ap.getPatient();
                if (patient != null && patient.getEmail() != null) {
                    emailService.sendAppointmentUpdatedByDoctor(
                            patient.getEmail(),
                            ap.getDoctor().getName(),
                            oldTime,
                            newTime
                    );
                }
            } catch (Exception mailEx) {
                mailEx.printStackTrace();
                System.out.println("Email failed, but update persisted: " + mailEx.getMessage());
            }

            return "redirect:/doctor/appointments/" + id;

        } catch (Exception e) {
            if (tx != null) tx.rollback();
            e.printStackTrace();
            model.addAttribute("msg", "Update failed.");
            return "redirect:/doctor/dashboard";
        } finally {
            ss.close();
        }
    }

    // ===================================================
    // CANCEL APPOINTMENT
    // ===================================================
    @PostMapping("/appointments/{id}/cancel")
    public String cancelAppointment(@PathVariable Long id,
                                    HttpSession session,
                                    Model model) {

        Long doctorId = toLong(session.getAttribute("doctorId"));
        if (doctorId == null) {
            model.addAttribute("msg", "Please login.");
            return "home";
        }

        Session ss = sf.openSession();
        Transaction tx = null;

        try {
            tx = ss.beginTransaction();
            Appointment ap = ss.get(Appointment.class, id);

            if (ap == null || !doctorId.equals(ap.getDoctor().getId())) {
                model.addAttribute("msg", "Access denied.");
                return "redirect:/doctor/dashboard";
            }

            // 1️⃣ Capture details for email BEFORE update
            Patient patient = ap.getPatient();
            String doctorName = ap.getDoctor().getName();
            LocalDateTime apptTime = ap.getAppointmentTime();

            // 2️⃣ Update Status
            ap.setStatus(AppointmentStatus.CANCELLED);
            ss.update(ap);
            tx.commit();

            // 3️⃣ Send Email (Wrapped in try-catch to prevent crashing)
            if (patient != null && patient.getEmail() != null) {
                try {
                    emailService.sendAppointmentCancelledByDoctor(
                        patient.getEmail(),
                        doctorName,
                        apptTime
                    );
                } catch (Exception e) {
                    System.err.println("Email sending failed: " + e.getMessage());
                }
            }

            return "redirect:/doctor/dashboard";

        } catch (Exception e) {
            if (tx != null) tx.rollback();
            model.addAttribute("msg", "Cancel failed.");
            return "redirect:/doctor/dashboard";
        } finally {
            ss.close();
        }
    }

    // ===================================================
    // MANAGE APPOINTMENTS (PAST)
    // ===================================================
    @GetMapping("/appointments/manage")
    public String manageAppointments(Model model, HttpSession session) {

        Long doctorId = toLong(session.getAttribute("doctorId"));
        if (doctorId == null) {
            model.addAttribute("msg", "Please login.");
            return "home";
        }

        Session ss = sf.openSession();
        try {
            Query<Appointment> q = ss.createQuery(
                "from Appointment a " +
                "where a.doctor.id = :did " +
                "and a.appointmentTime < :now " +
                "order by a.appointmentTime desc",
                Appointment.class
            );

            q.setParameter("did", doctorId);
            q.setParameter("now", LocalDateTime.now());

            model.addAttribute("appointments", q.list());
            return "doctor_manage_appointments";

        } finally {
            ss.close();
        }
    }

    // ===================================================
    // DELETE APPOINTMENT (FROM MANAGE PAGE)
    // ===================================================
    @PostMapping("/appointments/{id}/delete")
    public String deleteAppointment(@PathVariable Long id,
                                    HttpSession session,
                                    Model model) {

        Long doctorId = toLong(session.getAttribute("doctorId"));
        if (doctorId == null) {
            model.addAttribute("msg", "Please login.");
            return "home";
        }

        Session ss = sf.openSession();
        Transaction tx = null;

        try {
            tx = ss.beginTransaction();
            Appointment ap = ss.get(Appointment.class, id);

            if (ap == null || !doctorId.equals(ap.getDoctor().getId())) {
                model.addAttribute("msg", "Access denied.");
                return "redirect:/doctor/appointments/manage";
            }

            ss.delete(ap);
            tx.commit();

            return "redirect:/doctor/appointments/manage";

        } catch (Exception e) {
            if (tx != null) tx.rollback();
            model.addAttribute("msg", "Delete failed.");
            return "redirect:/doctor/appointments/manage";
        } finally {
            ss.close();
        }
    }

    // ===================================================
    // 💾 SAVE MEDICAL RECORD (AND EMAIL WITH ATTACHMENTS)
    // ===================================================
    @PostMapping("/records")
    public String saveMedicalRecord(@RequestParam Integer patientId,
                                    @RequestParam(required = false) Long appointmentId,
                                    @RequestParam String diagnosis,
                                    @RequestParam String prescription,
                                    @RequestParam String treatment,
                                    @RequestParam(required = false) String notes,
                                    @RequestParam(value = "files", required = false) MultipartFile[] files,
                                    HttpSession session,
                                    Model model) {

        Long doctorId = toLong(session.getAttribute("doctorId"));
        if (doctorId == null) {
            return "home";
        }

        Session ss = sf.openSession();
        Transaction tx = null;
        List<File> savedFilesForEmail = new ArrayList<>();

        try {
            tx = ss.beginTransaction();

            Doctor doctor = ss.get(Doctor.class, doctorId);
            Patient patient = ss.get(Patient.class, patientId);
            Appointment appointment = (appointmentId != null) ? ss.get(Appointment.class, appointmentId) : null;

            if (doctor == null || patient == null) {
                return "redirect:/doctor/dashboard";
            }

            // Create Record
            MedicalRecord record = new MedicalRecord();
            record.setDoctor(doctor);
            record.setPatient(patient);
            record.setAppointment(appointment);
            record.setRecordDate(java.time.LocalDate.now());
            record.setDiagnosis(diagnosis);
            record.setPrescription(prescription);
            record.setTreatment(treatment);
            record.setNotes(notes);

            ss.persist(record);

            // 📁 Handle File Uploads
            if (files != null) {
                for (MultipartFile f : files) {
                    if (f != null && !f.isEmpty()) {
                        String original = StringUtils.cleanPath(f.getOriginalFilename());
                        String ext = "";
                        int idx = original.lastIndexOf('.');
                        if (idx >= 0) ext = original.substring(idx);

                        String uniqueName = UUID.randomUUID().toString() + ext;
                        Path targetPath = storageDir.resolve(uniqueName);
                        
                        // Save to Disk
                        Files.copy(f.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);
                        
                        // Add to list for Email Attachment
                        savedFilesForEmail.add(targetPath.toFile());

                        // (Optional) Save metadata to DB if you have a RecordReport entity
                        // RecordReport rr = new RecordReport();
                        // rr.setFileName(uniqueName);
                        // rr.setMedicalRecord(record);
                        // ss.save(rr);
                    }
                }
            }
            
            // Mark appointment complete
            if (appointment != null) {
                appointment.setStatus(AppointmentStatus.COMPLETED);
                ss.update(appointment);
            }

            tx.commit();

            // 📧 Send Email with Attachments
            try {
                if (patient.getEmail() != null && !patient.getEmail().isEmpty()) {
                    System.out.println("Sending Medical Record Email with " + savedFilesForEmail.size() + " attachments.");
                    
                    emailService.sendMedicalRecordToPatient(
                        patient.getEmail(),
                        doctor.getName(),
                        record,
                        savedFilesForEmail // Passing list of files
                    );
                }
            } catch (Exception e) {
                System.err.println("Email failed to send: " + e.getMessage());
                e.printStackTrace();
            }

            return "redirect:/doctor/records";

        } catch (Exception e) {
            if (tx != null) tx.rollback();
            e.printStackTrace();
            return "redirect:/doctor/dashboard";
        } finally {
            ss.close();
        }
    }

    // ===================================================
    // DELETE MEDICAL RECORD
    // ===================================================
    @PostMapping("/records/{id}/delete")
    public String deleteMedicalRecord(@PathVariable Integer id,
                                      HttpSession session,
                                      Model model) {

        Long doctorId = toLong(session.getAttribute("doctorId"));
        if (doctorId == null) {
            model.addAttribute("msg", "Please login.");
            return "home";
        }

        Session ss = sf.openSession();
        Transaction tx = null;

        try {
            tx = ss.beginTransaction();

            MedicalRecord record = ss.get(MedicalRecord.class, id);

            // 🔐 Security check
            if (record == null ||
                record.getDoctor() == null ||
                !doctorId.equals(record.getDoctor().getId())) {

                model.addAttribute("msg", "Access denied.");
                return "redirect:/doctor/records";
            }

            ss.delete(record);
            tx.commit();

            return "redirect:/doctor/records";

        } catch (Exception e) {
            if (tx != null) tx.rollback();
            model.addAttribute("msg", "Failed to delete medical record.");
            return "redirect:/doctor/records";
        } finally {
            ss.close();
        }
    }

    // ===================================================
    // EMAIL MEDICAL RECORD (Existing Record - No New Attachments)
    // ===================================================
    @PostMapping("/records/{id}/email")
    public String emailMedicalRecord(@PathVariable Integer id,
                                     HttpSession session,
                                     Model model) {

        Long doctorId = toLong(session.getAttribute("doctorId"));
        if (doctorId == null) {
            return "home";
        }

        Session ss = sf.openSession();
        try {
            MedicalRecord record = ss.get(MedicalRecord.class, id);

            if (record == null || !doctorId.equals(record.getDoctor().getId())) {
                return "redirect:/doctor/records";
            }

            Patient patient = record.getPatient();
            if (patient != null && patient.getEmail() != null && !patient.getEmail().isEmpty()) {
                // Sending without new attachments for existing records
                emailService.sendMedicalRecordToPatient(
                    patient.getEmail(),
                    record.getDoctor().getName(),
                    record,
                    null 
                );
            }

            return "redirect:/doctor/records";

        } finally {
            ss.close();
        }
    }

    // ===================================================
    // LOGOUT
    // ===================================================
    @GetMapping("/logout")
    public String logout(HttpSession session) {
        session.invalidate();
        return "home";
    }
}