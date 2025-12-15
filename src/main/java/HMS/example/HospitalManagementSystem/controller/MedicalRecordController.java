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

import HMS.example.HospitalManagementSystem.model.*;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.FileInputStream;
import java.io.OutputStream;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Controller
public class MedicalRecordController {

    @Autowired
    private SessionFactory sf;

    // configure storage dir (change to config)
    private final Path storageDir = Paths.get(System.getProperty("user.dir"), "uploads", "reports");

    public MedicalRecordController() {
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

    // ---------- patient: list their records (with optional doctor filter) ----------
    @GetMapping("/patient/records")
    public String patientRecords(
            @RequestParam(value = "doctorId", required = false) Long doctorId,
            Model model, HttpSession session) {

        Long patientId = toLong(session.getAttribute("patientId"));
        if (patientId == null) {
            model.addAttribute("msg", "Please login as patient.");
            return "home";
        }

        Session ss = sf.openSession();
        try {
            // load doctors for dropdown (simple list)
            Query<Doctor> allDocQ = ss.createQuery("from Doctor d order by d.name", Doctor.class);
            List<Doctor> allDoctors = allDocQ.list();
            model.addAttribute("doctors", allDoctors);

            // Build HQL with optional doctor filter
            Query<MedicalRecord> q = ss.createQuery(
                    "select distinct m from MedicalRecord m " +
                    "left join fetch m.reports r " +
                    "left join fetch m.doctor d " +
                    "where m.patient.id = :pid " +
                    (doctorId != null ? "and d.id = :did " : "") +
                    "order by m.recordDate desc",
                    MedicalRecord.class);
            q.setParameter("pid", patientId);
            if (doctorId != null) {
                q.setParameter("did", doctorId);
                model.addAttribute("selectedDoctorId", doctorId);
            } else {
                model.addAttribute("selectedDoctorId", null);
            }

            List<MedicalRecord> list = q.list();
            System.out.println("[patientRecords] found " + (list == null ? 0 : list.size())
                    + " records for patientId=" + patientId + " doctorFilter=" + doctorId);
            model.addAttribute("records", list);
            model.addAttribute("patientName", session.getAttribute("patientName"));
            return "patient_medical_records";
        } finally {
            ss.close();
        }
    }

    // ---------- patient: view record details ----------
    @GetMapping("/patient/records/{id}")
    public String viewRecordForPatient(@PathVariable("id") Integer id, Model model, HttpSession session) {
        Long patientId = toLong(session.getAttribute("patientId"));
        if (patientId == null) {
            model.addAttribute("msg", "Please login as patient.");
            return "home";
        }

        Session ss = sf.openSession();
        try {
            // fetch record and eager-load doctor, appointment and reports to avoid LazyInitializationException
            Query<MedicalRecord> q = ss.createQuery(
                "select distinct m from MedicalRecord m " +
                "left join fetch m.reports r " +
                "left join fetch m.doctor d " +
                "left join fetch m.appointment a " +
                "where m.id = :id", MedicalRecord.class);
            q.setParameter("id", id);
            MedicalRecord mr = q.uniqueResult();

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

    // ---------- doctor: new record form ----------
    @GetMapping("/doctor/records/new")
    public String newRecordForm(@RequestParam(value = "patientId", required = false) Long patientId,
                                Model model, HttpSession session) {
        Long doctorId = toLong(session.getAttribute("doctorId"));
        if (doctorId == null) {
            model.addAttribute("msg", "Please login as doctor.");
            return "home";
        }
        Session ss = sf.openSession();
        try {
            if (patientId != null) {
                // try both Long and Integer PK lookups (safe)
                Object patientObj = null;
                try {
                    patientObj = ss.get(Patient.class, patientId);
                } catch (Exception ignored) {}
                if (patientObj == null) {
                    try {
                        patientObj = ss.get(Patient.class, patientId.intValue());
                    } catch (Exception ignored) {}
                }
                Patient p = (Patient) patientObj;
                model.addAttribute("patient", p);
            }
            model.addAttribute("doctorId", doctorId);
            return "doctor_add_record"; // template where doctor fills diagnosis/prescription + uploads
        } finally {
            ss.close();
        }
    }

    // ---------- doctor: create record (with file uploads) ----------
    @PostMapping("/doctor/records")
    public String createRecord(@RequestParam("patientId") Long patientId,
                               @RequestParam(value = "appointmentId", required = false) Long appointmentId,
                               @RequestParam("doctorId") Long doctorId,
                               @RequestParam("diagnosis") String diagnosis,
                               @RequestParam("prescription") String prescription,
                               @RequestParam("notes") String notes,
                               @RequestParam(value = "files", required = false) MultipartFile[] files,
                               Model model, HttpSession session) {

        Long docIdFromSession = toLong(session.getAttribute("doctorId"));
        if (docIdFromSession == null || !docIdFromSession.equals(doctorId)) {
            model.addAttribute("msg", "Please login as doctor.");
            return "home";
        }

        Session ss = sf.openSession();
        Transaction tx = null;
        try {
            tx = ss.beginTransaction();

            // --- fetch Patient safely (try Long then Integer PK) ---
            Patient p = null;
            if (patientId != null) {
                try { p = ss.get(Patient.class, patientId); } catch (Exception ignored) {}
                if (p == null) {
                    try { p = ss.get(Patient.class, patientId.intValue()); } catch (Exception ignored) {}
                }
            }
            System.out.println("[DEBUG] fetched patient = " + p);

            // --- fetch Doctor safely (try HQL with Long, if null try with Integer) ---
            Doctor d = null;
            try {
                Query<Doctor> dq = ss.createQuery("from Doctor d where d.id = :id", Doctor.class);
                dq.setParameter("id", doctorId);
                d = dq.uniqueResult();
            } catch (Exception ignored) {}
            if (d == null) {
                try {
                    Query<Doctor> dq2 = ss.createQuery("from Doctor d where d.id = :id", Doctor.class);
                    dq2.setParameter("id", doctorId != null ? doctorId.intValue() : null);
                    d = dq2.uniqueResult();
                } catch (Exception ignored) {}
            }
            System.out.println("[DEBUG] fetched doctor = " + d + " (doctorId param=" + doctorId + ")");

            if (p == null || d == null) {
                model.addAttribute("msg", "Patient or doctor not found. patient=" + p + " doctor=" + d);
                if (tx != null) tx.rollback();
                return "doctor_add_record";
            }

            MedicalRecord mr = new MedicalRecord();
            mr.setPatient(p);
            mr.setDoctor(d);
            mr.setRecordDate(LocalDate.now());
            mr.setDiagnosis(diagnosis);
            mr.setPrescription(prescription);
            mr.setNotes(notes);
            // you already have @PrePersist, setting timestamps here is fine for debugging
            mr.setCreatedAt(LocalDateTime.now());
            mr.setUpdatedAt(LocalDateTime.now());

            if (appointmentId != null) {
                // try both Long and Integer PK lookup for appointment
                Appointment ap = null;
                try { ap = ss.get(Appointment.class, appointmentId); } catch (Exception ignored) {}
                if (ap == null) {
                    try { ap = ss.get(Appointment.class, appointmentId.intValue()); } catch (Exception ignored) {}
                }
                System.out.println("[DEBUG] fetched appointment = " + ap);
                if (ap != null) mr.setAppointment(ap);
            }

            // persist medical record and flush to force SQL execution
            ss.save(mr);
            ss.flush();
            System.out.println("[DEBUG] saved MedicalRecord id=" + mr.getId());

            // handle file uploads (persist RecordReport rows referencing mr)
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
                        rr.setDescription(null);
                        rr.setMedicalRecord(mr);

                        // 🔥 REQUIRED FIX — set uploaded_at so DB does NOT throw error
                        rr.setUploadedAt(java.time.LocalDateTime.now());

                        ss.save(rr);
                    }
                }

                ss.flush(); // optional but useful to show SQL errors instantly
            }

            tx.commit();
            System.out.println("[DEBUG] transaction committed for MedicalRecord id=" + mr.getId());
            return "redirect:/doctor"; // back to doctor dashboard
        } catch (Exception ex) {
            if (tx != null) tx.rollback();
            ex.printStackTrace();
            model.addAttribute("msg", "Error saving record: " + ex.getMessage());
            return "doctor_add_record";
        } finally {
            ss.close();
        }
    }

    // ---------- download file ----------
    @GetMapping("/records/files/{id}")
    public void downloadFile(@PathVariable("id") Integer id, HttpServletResponse response) {
        Session ss = sf.openSession();
        try {
            Query<RecordReport> rq = ss.createQuery("from RecordReport r where r.id = :id", RecordReport.class);
            rq.setParameter("id", id);
            RecordReport rr = rq.uniqueResult();

            if (rr == null) {
                response.setStatus(404);
                return;
            }
            Path file = storageDir.resolve(rr.getFileName());
            if (!Files.exists(file)) {
                response.setStatus(404);
                return;
            }
            response.setContentType(rr.getContentType() != null ? rr.getContentType() : "application/octet-stream");
            response.setHeader("Content-Disposition", "attachment; filename=\"" + rr.getOriginalName() + "\"");
            try (FileInputStream in = new FileInputStream(file.toFile());
                 OutputStream out = response.getOutputStream()) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = in.read(buffer)) != -1) {
                    out.write(buffer, 0, len);
                }
            }
        } catch (Exception ex) {
            ex.printStackTrace();
            response.setStatus(500);
        } finally {
            ss.close();
        }
    }@GetMapping("/doctor/records")
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

            return "doctor_record_details"; // Create this page
        } finally {
            ss.close();
        }
    }

    // ---------- doctor: view record details ----------
    @GetMapping("/doctor/records/{id}")
    public String viewRecordForDoctor(@PathVariable("id") Integer id, Model model, HttpSession session) {
        Long doctorId = toLong(session.getAttribute("doctorId"));
        if (doctorId == null) {
            model.addAttribute("msg", "Please login as doctor.");
            return "home";
        }

        Session ss = sf.openSession();
        try {
            Query<MedicalRecord> q = ss.createQuery(
                "select distinct m from MedicalRecord m " +
                "left join fetch m.reports r " +
                "left join fetch m.patient p " +
                "left join fetch m.appointment a " +
                "where m.id = :id", MedicalRecord.class);
            q.setParameter("id", id);
            MedicalRecord mr = q.uniqueResult();

            if (mr == null || mr.getDoctor() == null || !doctorId.equals(toLong(mr.getDoctor().getId()))) {
                model.addAttribute("msg", "Record not found or access denied.");
                return "redirect:/doctor";
            }
            model.addAttribute("record", mr);
            return "doctor_record_details";
        } finally {
            ss.close();
        }
    }
}
