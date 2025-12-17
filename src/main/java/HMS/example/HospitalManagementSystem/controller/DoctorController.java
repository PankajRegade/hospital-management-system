package HMS.example.HospitalManagementSystem.controller;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.query.Query;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import HMS.example.HospitalManagementSystem.model.Appointment;
import HMS.example.HospitalManagementSystem.model.AppointmentStatus;
import HMS.example.HospitalManagementSystem.model.Doctor;
import HMS.example.HospitalManagementSystem.model.MedicalRecord;
import jakarta.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

@Controller
@RequestMapping("/doctor")
public class DoctorController {

    @Autowired
    private SessionFactory sf;

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

            ap.setAppointmentTime(LocalDateTime.parse(appointmentTime));
            ap.setStatus(AppointmentStatus.valueOf(status));
            ap.setNotes(notes);

            ss.update(ap);
            tx.commit();

            return "redirect:/doctor/appointments/" + id;

        } catch (Exception e) {
            if (tx != null) tx.rollback();
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

            ap.setStatus(AppointmentStatus.CANCELLED);
            ss.update(ap);
            tx.commit();

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
    // LOGOUT
    // ===================================================
    @GetMapping("/logout")
    public String logout(HttpSession session) {
        session.invalidate();
        return "home";
    }// ===================================================
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

}
