package HMS.example.HospitalManagementSystem.controller;

import org.hibernate.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import HMS.example.HospitalManagementSystem.model.*;
import HMS.example.HospitalManagementSystem.service.EmailService;
import jakarta.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.util.List;

@Controller
@RequestMapping("/appointments")
public class AppointmentController {

    private static final Logger log = LoggerFactory.getLogger(AppointmentController.class);

    @Autowired
    private SessionFactory sf;

    @Autowired
    private EmailService emailService;

    /* ================= UTIL ================= */
    private Long getID(HttpSession session, String key) {
        Object v = session.getAttribute(key);
        if (v == null) return null;
        if (v instanceof Number) return ((Number) v).longValue();
        return Long.parseLong(v.toString());
    }

    /* ================= BOOK APPOINTMENT ================= */
    @PostMapping("/book")
    public String book(@RequestParam Long doctorId,
                       @RequestParam String timeRaw,
                       Model model,
                       HttpSession session) {

        Long patientId = getID(session, "patientId");
        if (patientId == null)
            return msg(model, "Login required", "home");

        LocalDateTime appointmentTime;
        try {
            appointmentTime = LocalDateTime.parse(timeRaw);
        } catch (Exception e) {
            return msg(model, "Invalid date/time", "patient_doctors");
        }

        if (appointmentTime.isBefore(LocalDateTime.now()))
            return msg(model, "Cannot book past appointment", "patient_doctors");

        Session ss = sf.openSession();
        Transaction tx = ss.beginTransaction();

        try {
            Patient patient = ss.get(Patient.class, patientId);
            Doctor doctor = ss.get(Doctor.class, doctorId);

            if (patient == null || doctor == null)
                return msg(model, "Doctor or Patient not found", "home");

            // 🔍 clash check
            Long clash = ss.createQuery(
                    "select count(a.id) from Appointment a " +
                            "where a.doctor.id=:d and a.appointmentTime=:t and a.status='BOOKED'",
                    Long.class
            )
            .setParameter("d", doctorId)
            .setParameter("t", appointmentTime)
            .uniqueResult();

            if (clash != null && clash > 0)
                return msg(model, "Time slot unavailable", "patient_doctors");

            /* ===== STEP 1: SAVE ===== */
            Appointment ap = new Appointment();
            ap.setDoctor(doctor);
            ap.setPatient(patient);
            ap.setAppointmentTime(appointmentTime);
            ap.setStatus(AppointmentStatus.BOOKED);
            ap.setNotes("Booked via system");

            ss.save(ap);
            ss.flush(); // 🔥 forces AUTO_INCREMENT

            /* ===== STEP 2: GENERATE APPOINTMENT NUMBER ===== */
            String appointmentNo =
                    "APT-" +
                    String.format("%06d", ap.getId()) +
                    "-" +
                    String.format("%06d", patient.getId());

            ap.setAppointmentNumber(appointmentNo);

            ss.update(ap);
            tx.commit();

            // 📧 email
            if (patient.getEmail() != null) {
                try {
                    emailService.sendAppointmentConfirmation(patient.getEmail(), ap);
                } catch (Exception ignore) {}
            }

            return "redirect:/appointments/confirmation/" + ap.getId();

        } catch (Exception e) {
            tx.rollback();
            log.error("Booking failed", e);
            return msg(model, "Booking failed: " + e.getMessage(), "patient_doctors");
        } finally {
            ss.close();
        }
    }

    /* ================= CONFIRMATION ================= */
    @GetMapping("/confirmation/{id}")
    public String confirmation(@PathVariable Long id, Model model, HttpSession session) {

        Long pid = getID(session, "patientId");
        Appointment ap = fetch(id);

        if (ap == null || pid == null || !pid.equals(ap.getPatient().getId()))
            return msg(model, "Access denied", "home");

        model.addAttribute("appointment", ap);
        return "appointment_confirmation";
    }

    /* ================= PATIENT LIST ================= */
    @GetMapping("/patient")
    public String patientAppointments(Model model, HttpSession session) {

        Long pid = getID(session, "patientId");
        if (pid == null)
            return msg(model, "Login required", "home");

        Session ss = sf.openSession();
        List<Appointment> list = ss.createQuery(
                "from Appointment a where a.patient.id=:p order by a.appointmentTime desc",
                Appointment.class
        ).setParameter("p", pid).list();
        ss.close();

        model.addAttribute("appointments", list);
        model.addAttribute("patientName", session.getAttribute("patientName"));
        return "patient_appointments";
    }

    /* ================= DETAILS ================= */
    @GetMapping("/{id}")
    public String details(@PathVariable Long id, Model model, HttpSession session) {

        Appointment ap = fetch(id);
        if (ap == null) return msg(model, "Not found", "home");

        Long pid = getID(session, "patientId");
        Long did = getID(session, "doctorId");

        if (pid != null && pid.equals(ap.getPatient().getId())) {
            model.addAttribute("appointment", ap);
            return "patient_appointment_details";
        }

        if (did != null && did.equals(ap.getDoctor().getId())) {
            model.addAttribute("appointment", ap);
            return "doctor_appointment_details";
        }

        return msg(model, "Unauthorized", "home");
    }

    /* ================= CANCEL ================= */
    @PostMapping("/{id}/cancel")
    public String cancel(@PathVariable Long id, HttpSession session) {

        Long did = getID(session, "doctorId");
        Long pid = getID(session, "patientId");

        Session ss = sf.openSession();
        Transaction tx = ss.beginTransaction();

        Appointment ap = ss.get(Appointment.class, id);

        if (ap != null && (
                (did != null && did.equals(ap.getDoctor().getId())) ||
                (pid != null && pid.equals(ap.getPatient().getId()))
        )) {
            ap.setStatus(AppointmentStatus.CANCELLED);
            ss.update(ap);
            tx.commit();
        } else {
            tx.rollback();
        }

        ss.close();
        return (did != null) ? "redirect:/doctor/dashboard"
                             : "redirect:/appointments/patient";
    }

    /* ================= HELPERS ================= */
    private Appointment fetch(Long id) {
        Session s = sf.openSession();
        Appointment a = s.get(Appointment.class, id);
        s.close();
        return a;
    }

    private String msg(Model model, String msg, String page) {
        model.addAttribute("msg", msg);
        return page;
    }
}
