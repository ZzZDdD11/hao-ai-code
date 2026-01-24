package com.hao.haoaicode.controller;

import com.mybatisflex.core.paginate.Page;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.beans.factory.annotation.Autowired;
import com.hao.haoaicode.model.entity.UserWallet;
import com.hao.haoaicode.service.UserWalletService;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

/**
 * 用户钱包表 控制层。
 *
 * @author hao
 */
@RestController
@RequestMapping("/userWallet")
public class UserWalletController {

    @Autowired
    private UserWalletService userWalletService;


    /**
     * 保存用户钱包表。
     *
     * @param userWallet 用户钱包表
     * @return {@code true} 保存成功，{@code false} 保存失败
     */
    @PostMapping("save")
    public boolean save(@RequestBody UserWallet userWallet) {
        return userWalletService.save(userWallet);
    }

    /**
     * 根据主键删除用户钱包表。
     *
     * @param id 主键
     * @return {@code true} 删除成功，{@code false} 删除失败
     */
    @DeleteMapping("remove/{id}")
    public boolean remove(@PathVariable Long id) {
        return userWalletService.removeById(id);
    }

    /**
     * 根据主键更新用户钱包表。
     *
     * @param userWallet 用户钱包表
     * @return {@code true} 更新成功，{@code false} 更新失败
     */
    @PutMapping("update")
    public boolean update(@RequestBody UserWallet userWallet) {
        return userWalletService.updateById(userWallet);
    }

    /**
     * 查询所有用户钱包表。
     *
     * @return 所有数据
     */
    @GetMapping("list")
    public List<UserWallet> list() {
        return userWalletService.list();
    }

    /**
     * 根据主键获取用户钱包表。
     *
     * @param id 用户钱包表主键
     * @return 用户钱包表详情
     */
    @GetMapping("getInfo/{id}")
    public UserWallet getInfo(@PathVariable Long id) {
        return userWalletService.getById(id);
    }

    /**
     * 分页查询用户钱包表。
     *
     * @param page 分页对象
     * @return 分页对象
     */
    @GetMapping("page")
    public Page<UserWallet> page(Page<UserWallet> page) {
        return userWalletService.page(page);
    }

}
