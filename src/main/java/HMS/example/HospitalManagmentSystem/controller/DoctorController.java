package HMS.example.HospitalManagmentSystem.controller;

import HMS.example.HospitalManagmentSystem.model.Appointment;
import HMS.example.HospitalManagmentSystem.model.AppointmentStatus;
import HMS.example.HospitalManagmentSystem.model.Doctor;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.query.Query;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

@Controller
@RequestMapping("/doctor")
public class DoctorController {

    @Autowired
    private SessionFactory sf;

    // ------------ helper to convert session id types ------------
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

    // ------------ Doctor dashboard: show profile + own appointments ------------
    @GetMapping({"", "/", "/dashboard"})
    public String dashboard(Model model, HttpSession session) {
        Long doctorId = toLong(session.getAttribute("doctorId"));
        if (doctorId == null) {
            model.addAttribute("msg", "Please login as doctor.");
            return "home";
        }

        Session ss = sf.openSession();
        try {
            // 1) Load doctor profile from DB
            Doctor doctor = ss.get(Doctor.class, doctorId);
            if (doctor == null) {
                model.addAttribute("msg", "Doctor record not found. Please contact admin.");
                return "home";
            }

            // Put full doctor object on model for dashboard
            model.addAttribute("doctor", doctor);

            // ---- NEW LINE (merged): expose detailsCompleted flag to the view ----
            model.addAttribute("detailsCompleted", doctor.getDetailsCompleted());

            // Also set doctorName used in header
            String displayName = (doctor.getName() != null && !doctor.getName().trim().isEmpty())
                    ? doctor.getName().trim()
                    : String.valueOf(session.getAttribute("doctorName"));
            model.addAttribute("doctorName", displayName);

            // 2) Load this doctor’s appointments
            Query<Appointment> q = ss.createQuery(
                    "from Appointment a where a.doctor.id = :did order by a.appointmentTime desc",
                    Appointment.class
            );
            q.setParameter("did", doctorId);

            List<Appointment> appointments = q.list();
            model.addAttribute(
                    "appointments",
                    appointments != null ? appointments : Collections.emptyList()
            );

            return "doctor_dashboard";
        } finally {
            ss.close();
        }
    }

    // ------------ View a single appointment (doctor owns it) ------------
    @GetMapping("/appointments/{id}")
    public String viewAppointment(@PathVariable("id") Long id,
                                  Model model,
                                  HttpSession session) {
        Long doctorId = toLong(session.getAttribute("doctorId"));
        if (doctorId == null) {
            model.addAttribute("msg", "Please login as doctor.");
            return "home";
        }

        Session ss = sf.openSession();
        try {
            Appointment ap = ss.get(Appointment.class, id);
            if (ap == null) {
                model.addAttribute("msg", "Appointment not found.");
                return "redirect:/doctor/dashboard";
            }
            if (ap.getDoctor() == null ||
                !doctorId.equals(toLong(ap.getDoctor().getId()))) {

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

    // ------------ Edit appointment (GET - show form) ------------
    @GetMapping("/appointments/{id}/edit")
    public String editAppointmentForm(@PathVariable("id") Long id,
                                      Model model,
                                      HttpSession session) {
        Long doctorId = toLong(session.getAttribute("doctorId"));
        if (doctorId == null) {
            model.addAttribute("msg", "Please login as doctor.");
            return "home";
        }

        Session ss = sf.openSession();
        try {
            Appointment ap = ss.get(Appointment.class, id);
            if (ap == null ||
                ap.getDoctor() == null ||
                !doctorId.equals(toLong(ap.getDoctor().getId()))) {

                model.addAttribute("msg", "Appointment not found or access denied.");
                return "redirect:/doctor/dashboard";
            }

            model.addAttribute("appointment", ap);
            model.addAttribute("doctorName", session.getAttribute("doctorName"));
            return "doctor_appointment_edit";
        } finally {
            ss.close();
        }
    }

    // ------------ Edit appointment (POST - save changes) ------------
    @PostMapping("/appointments/{id}/update")
    public String updateAppointment(@PathVariable("id") Long id,
                                    @RequestParam("appointmentTime") String appointmentTimeRaw,
                                    @RequestParam("status") String status,
                                    @RequestParam(value = "notes", required = false) String notes,
                                    Model model,
                                    HttpSession session) {

        Long doctorId = toLong(session.getAttribute("doctorId"));
        if (doctorId == null) {
            model.addAttribute("msg", "Please login as doctor.");
            return "home";
        }

        Session ss = sf.openSession();
        Transaction tx = null;
        try {
            tx = ss.beginTransaction();

            Appointment ap = ss.get(Appointment.class, id);
            if (ap == null ||
                ap.getDoctor() == null ||
                !doctorId.equals(toLong(ap.getDoctor().getId()))) {

                if (tx != null) tx.rollback();
                model.addAttribute("msg", "Appointment not found or access denied.");
                return "redirect:/doctor/dashboard";
            }

            LocalDateTime appointmentTime = LocalDateTime.parse(appointmentTimeRaw);

            ap.setAppointmentTime(appointmentTime);
            ap.setStatus(AppointmentStatus.valueOf(status));
            ap.setNotes(notes);

            ss.update(ap);
            tx.commit();

            model.addAttribute("msg", "Appointment updated successfully.");
            return "redirect:/doctor/appointments/" + id;
        } catch (Exception ex) {
            if (tx != null) tx.rollback();
            model.addAttribute("msg", "Error updating appointment: " + ex.getMessage());
            return "redirect:/doctor/dashboard";
        } finally {
            ss.close();
        }
    }

    // ------------ Cancel appointment ------------
    @PostMapping("/appointments/{id}/cancel")
    public String cancelAppointment(@PathVariable("id") Long id,
                                    Model model,
                                    HttpSession session) {
        Long doctorId = toLong(session.getAttribute("doctorId"));
        if (doctorId == null) {
            model.addAttribute("msg", "Please login as doctor.");
            return "home";
        }

        Session ss = sf.openSession();
        Transaction tx = null;
        try {
            tx = ss.beginTransaction();
            Appointment ap = ss.get(Appointment.class, id);
            if (ap == null ||
                ap.getDoctor() == null ||
                !doctorId.equals(toLong(ap.getDoctor().getId()))) {

                if (tx != null) tx.rollback();
                model.addAttribute("msg", "Appointment not found or access denied.");
                return "redirect:/doctor/dashboard";
            }

            ap.setStatus(AppointmentStatus.CANCELLED);
            ss.update(ap);
            tx.commit();

            model.addAttribute("msg", "Appointment canceled.");
            return "redirect:/doctor/dashboard";
        } catch (Exception ex) {
            if (tx != null) tx.rollback();
            model.addAttribute("msg", "Error: " + ex.getMessage());
            return "redirect:/doctor/dashboard";
        } finally {
            ss.close();
        }
    }

    // ------------ Delete appointment ------------
    @PostMapping("/appointments/{id}/delete")
    public String deleteAppointment(@PathVariable("id") Long id,
                                    Model model,
                                    HttpSession session) {
        Long doctorId = toLong(session.getAttribute("doctorId"));
        if (doctorId == null) {
            model.addAttribute("msg", "Please login as doctor.");
            return "home";
        }

        Session ss = sf.openSession();
        Transaction tx = null;
        try {
            tx = ss.beginTransaction();
            Appointment ap = ss.get(Appointment.class, id);
            if (ap == null ||
                ap.getDoctor() == null ||
                !doctorId.equals(toLong(ap.getDoctor().getId()))) {

                if (tx != null) tx.rollback();
                model.addAttribute("msg", "Appointment not found or access denied.");
                return "redirect:/doctor/dashboard";
            }

            ss.delete(ap);
            tx.commit();

            model.addAttribute("msg", "Appointment deleted.");
            return "redirect:/doctor/dashboard";
        } catch (Exception ex) {
            if (tx != null) tx.rollback();
            model.addAttribute("msg", "Error: " + ex.getMessage());
            return "redirect:/doctor/dashboard";
        } finally {
            ss.close();
        }
    }

    // ------------ Logout ------------
    @GetMapping("/logout")
    public String logoutDoctor(HttpSession session) {
        session.invalidate();
        return "home";
    }
}
