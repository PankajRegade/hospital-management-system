package HMS.example.HospitalManagmentSystem;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;

import HMS.example.HospitalManagmentSystem.model.Login;



@Controller
public class HMSControllr {
	@Autowired
	SessionFactory sf;
	
	@RequestMapping("/")
 public String loginPage(){
	 
 return "login";
 }
	@RequestMapping("/login")
public	String logim(@ModelAttribute Login login,Model model)
	{
		Session ss=sf.openSession();
		Login dblogin=ss.get(Login.class, login.getUsername());
		String page="login";
		String msg=null;
	    if (dblogin !=null) {
			if (login.getPassword().equals(dblogin.getPassword())) {
				
			page="home";
				
			} else {
				msg="invalid password";

			}
		} else {
			msg="invalid username";
		} {
			
		}
		model.addAttribute("msg",msg);
		return page;
		
	}
	
	@RequestMapping("/signupPage")
	public String signupPage() {
		return "signup";
	}
	
	
	@RequestMapping("/singup")
	public String singup(@ModelAttribute Login login,Model model ) {
		Session ss=sf.openSession();
		Transaction tx=ss.beginTransaction();
		ss.save(login);
		tx.commit();
		return null;
		
	}
	@RequestMapping("/service")
	public String service() {
		return "service";
	}
}