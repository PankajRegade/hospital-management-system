package HMS.example.HospitalManagementSystem.controller;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.query.Query;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import HMS.example.HospitalManagementSystem.model.*;
import jakarta.servlet.http.HttpSession;

import java.nio.file.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Controller
public class MedicalRecordController {

    @Autowired
    private SessionFactory sf;

    // Define storage location for uploaded files
    private final Path storageDir = Paths.get(System.getProperty("user.dir"), "uploads", "reports");

    public MedicalRecordController() {
        // Create upload directory if it doesn't exist
        try {
            Files.createDirectories(storageDir);
        } catch (Exception e) {
            System.out.println("Could not create storage dir: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ---------- helper: convert various session-stored id types to Long ----------
    private Long toLong(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Long) return (Long) obj;
        if (obj instanceof Integer) return ((Integer) obj).longValue();
        if (obj instanceof Number) return ((Number) obj).longValue();
        if (obj instanceof String) {
            try { return Long.parseLong(((String) obj).trim()); } catch (Exception ignored) {}
        }
        return null;
    }

    // =========================================================================
    //                             PATIENT VIEW
    // =========================================================================

    // List all records for the logged-in patient
    @GetMapping("/patient/records")
    public String patientRecords(@RequestParam(value = "doctorId", required = false) Long doctorId,
                                 Model model, HttpSession session) {

        Long patientId = toLong(session.getAttribute("patientId"));
        if (patientId == null) {
            model.addAttribute("msg", "Please login as patient.");
            return "home";
        }

        Session ss = sf.openSession();
        try {
            // Load doctors for filter dropdown
            Query<Doctor> allDocQ = ss.createQuery("from Doctor d order by d.name", Doctor.class);
            List<Doctor> allDoctors = allDocQ.list();
            model.addAttribute("doctors", allDoctors);

            // Fetch records
            String hql = "select distinct m from MedicalRecord m " +
                         "left join fetch m.reports r " +
                         "left join fetch m.doctor d " +
                         "where m.patient.id = :pid " +
                         (doctorId != null ? "and d.id = :did " : "") +
                         "order by m.recordDate desc";

            Query<MedicalRecord> q = ss.createQuery(hql, MedicalRecord.class);
            q.setParameter("pid", patientId);
            if (doctorId != null) {
                q.setParameter("did", doctorId);
                model.addAttribute("selectedDoctorId", doctorId);
            } else {
                model.addAttribute("selectedDoctorId", null);
            }

            List<MedicalRecord> list = q.list();
            model.addAttribute("records", list);
            model.addAttribute("patientName", session.getAttribute("patientName"));
            return "patient_medical_records";
        } finally {
            ss.close();
        }
    }

    // View specific record details (Patient)
    @GetMapping("/patient/records/{id}")
    public String viewRecordForPatient(@PathVariable("id") Integer id, Model model, HttpSession session) {
        Long patientId = toLong(session.getAttribute("patientId"));
        if (patientId == null) {
            model.addAttribute("msg", "Please login as patient.");
            return "home";
        }

        Session ss = sf.openSession();
        try {
            Query<MedicalRecord> q = ss.createQuery(
                "select distinct m from MedicalRecord m " +
                "left join fetch m.reports r " +
                "left join fetch m.doctor d " +
                "left join fetch m.appointment a " +
                "where m.id = :id", MedicalRecord.class);
            q.setParameter("id", id);
            MedicalRecord mr = q.uniqueResult();

            // Verify ownership
            if (mr == null || mr.getPatient() == null || !patientId.equals(toLong(mr.getPatient().getId()))) {
                model.addAttribute("msg", "Record not found or access denied.");
                return "redirect:/patient/records";
            }
            model.addAttribute("record", mr);
            model.addAttribute("patientName", session.getAttribute("patientName"));
            return "patient_record_details";
        } finally {
            ss.close();
        }
    }

    // =========================================================================
    //                             DOCTOR VIEW
    // =========================================================================

    // List all records created by this doctor
    @GetMapping("/doctor/records")
    public String doctorAllRecords(Model model, HttpSession session) {

        Long doctorId = toLong(session.getAttribute("doctorId"));
        if (doctorId == null) {
            model.addAttribute("msg", "Please login as doctor.");
            return "home";
        }

        Session ss = sf.openSession();
        try {
            Query<MedicalRecord> q = ss.createQuery(
                "select distinct m from MedicalRecord m " +
                "left join fetch m.patient p " +
                "left join fetch m.reports r " +
                "where m.doctor.id = :docId " +
                "order by m.recordDate desc",
                MedicalRecord.class
            );
            q.setParameter("docId", doctorId);

            List<MedicalRecord> list = q.list();
            model.addAttribute("records", list);
            model.addAttribute("doctorName", session.getAttribute("doctorName"));

            return "doctor_record_details"; 
        } finally {
            ss.close();
        }
    }

    // Show form to add NEW record
    @GetMapping("/doctor/records/new")
    public String newRecordForm(@RequestParam(value = "appointmentId", required = false) Long appointmentId,
                                Model model, HttpSession session) {

        Long doctorId = toLong(session.getAttribute("doctorId"));
        if (doctorId == null) {
            model.addAttribute("msg", "Please login as doctor.");
            return "home";
        }

        Session ss = sf.openSession();
        try {
            model.addAttribute("doctorId", doctorId);

            if (appointmentId != null) {
                Appointment ap = ss.get(Appointment.class, appointmentId);
                if (ap != null && ap.getDoctor().getId().equals(doctorId)) {
                    model.addAttribute("appointment", ap);
                    model.addAttribute("appointmentId", ap.getId());
                    model.addAttribute("patient", ap.getPatient());
                }
            }
            return "doctor_add_record";
        } finally {
            ss.close();
        }
    }

    // ❌ REMOVED "createRecord" (@PostMapping("/doctor/records")) TO FIX CONFLICT ❌
    // The DoctorController.java now handles saving the record.

    // =========================================================================
    //                             EDIT / UPDATE LOGIC
    // =========================================================================

    // Show form to EDIT existing record
    @GetMapping("/doctor/records/edit/{id}")
    public String editRecordForm(@PathVariable("id") Integer id, Model model, HttpSession session) {
        
        Long doctorId = toLong(session.getAttribute("doctorId"));
        if (doctorId == null) {
            model.addAttribute("msg", "Please login as doctor.");
            return "home";
        }

        Session ss = sf.openSession();
        try {
            Query<MedicalRecord> q = ss.createQuery(
                "from MedicalRecord m left join fetch m.reports where m.id = :id", 
                MedicalRecord.class
            );
            q.setParameter("id", id);
            MedicalRecord mr = q.uniqueResult();

            if (mr == null) {
                model.addAttribute("msg", "Record not found.");
                return "redirect:/doctor/records";
            }
            // Check if logged-in doctor owns this record
            if (!toLong(mr.getDoctor().getId()).equals(doctorId)) {
                model.addAttribute("msg", "You do not have permission to edit this record.");
                return "redirect:/doctor/records";
            }

            model.addAttribute("record", mr);
            model.addAttribute("patient", mr.getPatient()); 
            
            return "doctor_edit_record"; 

        } finally {
            ss.close();
        }
    }

    // Process UPDATE of existing record
    @PostMapping("/doctor/records/update")
    public String updateRecord(@RequestParam("id") Integer id,
                               @RequestParam("diagnosis") String diagnosis,
                               @RequestParam("prescription") String prescription,
                               @RequestParam("notes") String notes,
                               @RequestParam(value = "files", required = false) MultipartFile[] files,
                               HttpSession session, 
                               Model model) {

        Long doctorId = toLong(session.getAttribute("doctorId"));
        if (doctorId == null) {
            return "redirect:/home";
        }

        Session ss = sf.openSession();
        Transaction tx = null;

        try {
            tx = ss.beginTransaction();
            MedicalRecord mr = ss.get(MedicalRecord.class, id);

            // Security Check
            if (mr == null || !toLong(mr.getDoctor().getId()).equals(doctorId)) {
                if(tx != null) tx.rollback();
                return "redirect:/doctor/records?error=Unauthorized";
            }

            // Update text fields
            mr.setDiagnosis(diagnosis);
            mr.setPrescription(prescription);
            mr.setNotes(notes);
            mr.setUpdatedAt(LocalDateTime.now());

            ss.update(mr);

            // Add NEW files (existing files remain untouched)
            handleFileUploads(files, mr, ss);

            tx.commit();
            return "redirect:/doctor/records"; // Success

        } catch (Exception ex) {
            if (tx != null) tx.rollback();
            ex.printStackTrace();
            model.addAttribute("msg", "Error updating record: " + ex.getMessage());
            return "doctor_edit_record";
        } finally {
            ss.close();
        }
    }

    // =========================================================================
    //                             UTILITIES
    // =========================================================================

    // Helper method to save files to disk and DB
    private void handleFileUploads(MultipartFile[] files, MedicalRecord mr, Session ss) throws Exception {
        if (files != null) {
            for (MultipartFile f : files) {
                if (f != null && !f.isEmpty()) {
                    String original = StringUtils.cleanPath(f.getOriginalFilename());
                    String ext = "";
                    int idx = original.lastIndexOf('.');
                    if (idx >= 0) ext = original.substring(idx);

                    String unique = UUID.randomUUID().toString() + ext;
                    Path target = storageDir.resolve(unique);
                    Files.copy(f.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);

                    RecordReport rr = new RecordReport();
                    rr.setFileName(unique);
                    rr.setOriginalName(original);
                    rr.setContentType(f.getContentType());
                    rr.setMedicalRecord(mr);
                    rr.setUploadedAt(LocalDateTime.now());

                    ss.save(rr);
                }
            }
        }
    }

    // Download File Endpoint
    @GetMapping("/records/files/{id}")
    public ResponseEntity<byte[]> downloadFile(@PathVariable("id") Integer id) {
        Session ss = sf.openSession();
        try {
            RecordReport rr = ss.get(RecordReport.class, id);
            if (rr == null) return ResponseEntity.notFound().build();

            Path file = storageDir.resolve(rr.getFileName());
            if (!Files.exists(file)) return ResponseEntity.notFound().build();

            byte[] data = Files.readAllBytes(file);

            return ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + rr.getOriginalName() + "\"")
                .header(org.springframework.http.HttpHeaders.CONTENT_TYPE,
                        rr.getContentType() != null ? rr.getContentType() : "application/pdf")
                .header(org.springframework.http.HttpHeaders.CONTENT_LENGTH,
                        String.valueOf(data.length))
                .body(data);

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().build();
        } finally {
            ss.close();
        }
    }
}