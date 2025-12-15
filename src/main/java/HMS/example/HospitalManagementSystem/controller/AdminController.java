package HMS.example.HospitalManagementSystem.controller;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.query.Query;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import HMS.example.HospitalManagementSystem.model.Doctor;
import HMS.example.HospitalManagementSystem.model.Patient;
import jakarta.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.util.List;

@Controller
@RequestMapping("/admin")
public class AdminController {

    @Autowired
    private SessionFactory sf;

    @GetMapping("/dashboard")
    public String dashboard(Model model, HttpSession session) {

        Session ss = sf.openSession();
        try {

            Long totalDoctors = ss.createQuery(
                    "select count(d.id) from Doctor d", Long.class
            ).uniqueResult();

            Long pendingCount = ss.createQuery(
                    "select count(d.id) from Doctor d where d.approved = false or d.approved is null",
                    Long.class
            ).uniqueResult();

            Long approvedCount = ss.createQuery(
                    "select count(d.id) from Doctor d where d.approved = true",
                    Long.class
            ).uniqueResult();

            // pending doctor list
            List<Doctor> pendingDoctors = ss.createQuery(
                    "from Doctor d where d.approved = false or d.approved is null order by d.name",
                    Doctor.class
            ).list();

            model.addAttribute("totalDoctors", totalDoctors != null ? totalDoctors : 0L);
            model.addAttribute("pendingCount", pendingCount != null ? pendingCount : 0L);
            model.addAttribute("approvedCount", approvedCount != null ? approvedCount : 0L);
            model.addAttribute("pendingDoctors", pendingDoctors);

        } finally {
            ss.close();
        }

        return "admin_dashboard";
    }




    // ----------------- DOCTORS LIST PAGE (ONLY APPROVED DOCTORS) -----------------
    @GetMapping("/doctors")
    public String doctorsPage(Model model) {
        Session ss = sf.openSession();
        try {
            List<Doctor> doctors = ss.createQuery(
                    "from Doctor d where d.approved = true order by d.name",
                    Doctor.class
            ).list();

            model.addAttribute("doctors", doctors);

        } finally {
            ss.close();
        }
        return "admin_doctors";
    }


    // ❌ NO /doctors/new – admin will NOT create doctors manually

    // ----------------- EDIT DOCTOR (ONLY FIELDS FROM Doctor.java) -----------------
    @GetMapping("/doctors/edit/{id}")
    public String editDoctorForm(@PathVariable("id") Long id,
                                 Model model,
                                 RedirectAttributes ra) {

        Session ss = sf.openSession();
        try {
            Doctor d = ss.get(Doctor.class, id);
            if (d == null) {
                ra.addFlashAttribute("msg", "Doctor not found.");
                return "redirect:/admin/doctors";
            }
            model.addAttribute("doctor", d);
            return "admin_doctor_form";   // form with name/email/phone/specialization
        } finally {
            ss.close();
        }
    }

    @PostMapping("/doctors/edit/{id}")
    public String updateDoctor(@PathVariable("id") Long id,
                               @ModelAttribute Doctor formDoctor,
                               RedirectAttributes ra) {

        Session ss = sf.openSession();
        Transaction tx = null;
        try {
            tx = ss.beginTransaction();
            Doctor d = ss.get(Doctor.class, id);
            if (d == null) {
                if (tx != null) tx.rollback();
                ra.addFlashAttribute("msg", "Doctor not found.");
                return "redirect:/admin/doctors";
            }

            d.setName(formDoctor.getName());
            d.setEmail(formDoctor.getEmail());
            d.setPhone(formDoctor.getPhone());
            d.setSpecialization(formDoctor.getSpecialization());
            // approved & approvedAt only changed via approve/reject
            ss.update(d);

            tx.commit();

            ra.addFlashAttribute("msg", "Doctor updated successfully.");
            return "redirect:/admin/doctors";
        } catch (Exception ex) {
            if (tx != null) tx.rollback();
            ex.printStackTrace();
            ra.addFlashAttribute("msg", "Error updating doctor: " + ex.getMessage());
            return "redirect:/admin/doctors";
        } finally {
            ss.close();
        }
    }

    // ----------------- APPROVE / REJECT / DELETE DOCTOR -----------------
    @PostMapping("/doctors/{id}/approve")
    public String approveDoctor(@PathVariable("id") Long id,
                                RedirectAttributes ra) {

        Session ss = sf.openSession();
        Transaction tx = null;
        try {
            tx = ss.beginTransaction();
            Doctor d = ss.get(Doctor.class, id);
            if (d == null) {
                if (tx != null) tx.rollback();
                ra.addFlashAttribute("msg", "Doctor not found.");
                return "redirect:/admin/dashboard"; // come back to dashboard
            }

            d.setApproved(true);
            d.setApprovedAt(LocalDateTime.now());
            ss.update(d);
            tx.commit();

            ra.addFlashAttribute("msg", "Doctor '" + d.getName() + "' approved successfully.");
            return "redirect:/admin/dashboard";
        } catch (Exception ex) {
            if (tx != null) tx.rollback();
            ex.printStackTrace();
            ra.addFlashAttribute("msg", "Error approving doctor: " + ex.getMessage());
            return "redirect:/admin/dashboard";
        } finally {
            ss.close();
        }
    }

    @PostMapping("/doctors/{id}/reject")
    public String rejectDoctor(@PathVariable("id") Long id,
                               RedirectAttributes ra) {

        Session ss = sf.openSession();
        Transaction tx = null;
        try {
            tx = ss.beginTransaction();
            Doctor d = ss.get(Doctor.class, id);
            if (d == null) {
                if (tx != null) tx.rollback();
                ra.addFlashAttribute("msg", "Doctor not found.");
                return "redirect:/admin/dashboard";
            }

            ss.delete(d);
            tx.commit();

            ra.addFlashAttribute("msg", "Doctor '" + d.getName() + "' rejected and removed.");
            return "redirect:/admin/dashboard";
        } catch (Exception ex) {
            if (tx != null) tx.rollback();
            ex.printStackTrace();
            ra.addFlashAttribute("msg", "Error rejecting doctor: " + ex.getMessage());
            return "redirect:/admin/dashboard";
        } finally {
            ss.close();
        }
    }

    @PostMapping("/doctors/delete/{id}")
    public String deleteDoctor(@PathVariable("id") Long id,
                               RedirectAttributes ra) {

        Session ss = sf.openSession();
        Transaction tx = null;
        try {
            tx = ss.beginTransaction();
            Doctor d = ss.get(Doctor.class, id);
            if (d == null) {
                if (tx != null) tx.rollback();
                ra.addFlashAttribute("msg", "Doctor not found.");
                return "redirect:/admin/doctors";
            }

            ss.delete(d);
            tx.commit();

            ra.addFlashAttribute("msg", "Doctor '" + d.getName() + "' deleted successfully.");
            return "redirect:/admin/doctors";
        } catch (Exception ex) {
            if (tx != null) tx.rollback();
            ex.printStackTrace();
            ra.addFlashAttribute("msg", "Error deleting doctor: " + ex.getMessage());
            return "redirect:/admin/doctors";
        } finally {
            ss.close();
        }
    }

    // ----------------- PATIENT CRUD (ADMIN CAN ADD MANUALLY) -----------------
    @GetMapping("/patients")
    public String listPatients(Model model) {
        Session ss = sf.openSession();
        try {
            Query<Patient> pq = ss.createQuery("from Patient order by name", Patient.class);
            List<Patient> patients = pq.list();
            model.addAttribute("patients", patients);
            return "admin_patients";
        } finally {
            ss.close();
        }
    }

    @GetMapping("/patients/new")
    public String newPatientForm(Model model) {
        model.addAttribute("patient", new Patient());
        return "admin_patient_form";
    }

    @PostMapping("/patients/new")
    public String saveNewPatient(@ModelAttribute Patient patient,
                                 RedirectAttributes ra) {

        Session ss = sf.openSession();
        Transaction tx = null;
        try {
            tx = ss.beginTransaction();
            ss.save(patient);
            tx.commit();

            ra.addFlashAttribute("msg", "Patient '" + patient.getName() + "' added successfully.");
            return "redirect:/admin/patients";
        } catch (Exception ex) {
            if (tx != null) tx.rollback();
            ex.printStackTrace();
            ra.addFlashAttribute("msg", "Error saving patient: " + ex.getMessage());
            return "redirect:/admin/patients";
        } finally {
            ss.close();
        }
    }

    @GetMapping("/patients/edit/{id}")
    public String editPatientForm(@PathVariable("id") Long id,
                                  Model model,
                                  RedirectAttributes ra) {

        Session ss = sf.openSession();
        try {
            Patient p = ss.get(Patient.class, id);
            if (p == null) {
                ra.addFlashAttribute("msg", "Patient not found.");
                return "redirect:/admin/patients";
            }
            model.addAttribute("patient", p);
            return "admin_patient_form";
        } finally {
            ss.close();
        }
    }

    @PostMapping("/patients/edit/{id}")
    public String updatePatient(@PathVariable("id") Long id,
                                @ModelAttribute Patient formPatient,
                                RedirectAttributes ra) {

        Session ss = sf.openSession();
        Transaction tx = null;
        try {
            tx = ss.beginTransaction();
            Patient p = ss.get(Patient.class, id);
            if (p == null) {
                if (tx != null) tx.rollback();
                ra.addFlashAttribute("msg", "Patient not found.");
                return "redirect:/admin/patients";
            }

            p.setName(formPatient.getName());
            p.setEmail(formPatient.getEmail());
            p.setPhone(formPatient.getPhone());
            p.setAddress(formPatient.getAddress());
            p.setGender(formPatient.getGender());
            p.setAge(formPatient.getAge());
            p.setDisease(formPatient.getDisease());

            ss.update(p);
            tx.commit();

            ra.addFlashAttribute("msg", "Patient updated successfully.");
            return "redirect:/admin/patients";
        } catch (Exception ex) {
            if (tx != null) tx.rollback();
            ex.printStackTrace();
            ra.addFlashAttribute("msg", "Error updating patient: " + ex.getMessage());
            return "redirect:/admin/patients";
        } finally {
            ss.close();
        }
    }

    @PostMapping("/patients/delete/{id}")
    public String deletePatient(@PathVariable("id") Long id,
                                RedirectAttributes ra) {

        Session ss = sf.openSession();
        Transaction tx = null;
        try {
            tx = ss.beginTransaction();
            Patient p = ss.get(Patient.class, id);
            if (p == null) {
                if (tx != null) tx.rollback();
                ra.addFlashAttribute("msg", "Patient not found.");
                return "redirect:/admin/patients";
            }

            ss.delete(p);
            tx.commit();

            ra.addFlashAttribute("msg", "Patient '" + p.getName() + "' deleted successfully.");
            return "redirect:/admin/patients";
        } catch (Exception ex) {
            if (tx != null) tx.rollback();
            ex.printStackTrace();
            ra.addFlashAttribute("msg", "Error deleting patient: " + ex.getMessage());
            return "redirect:/admin/patients";
        } finally {
            ss.close();
        }
    }
}
